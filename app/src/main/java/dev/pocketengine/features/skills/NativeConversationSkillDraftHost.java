package dev.pocketengine.features.skills;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Conversation drafts stay in their original operations arguments/receipts. Only an
 * opaque owner review can adopt the exact files as a disabled, independent local skill. */
public final class NativeConversationSkillDraftHost {
    @FunctionalInterface public interface SourceGuard {JSONObject withCurrent(JSONArray refs,java.util.concurrent.Callable<JSONObject> action)throws Exception;}
    private final TaskStore tasks;
    private final SkillsModule skills;
    private final SourceGuard sources;
    public NativeConversationSkillDraftHost(TaskStore tasks,SkillsModule skills,SourceGuard sources){this.tasks=java.util.Objects.requireNonNull(tasks);this.skills=java.util.Objects.requireNonNull(skills);this.sources=java.util.Objects.requireNonNull(sources);}

    /** Supported by the existing bounded PluginRules validator; no free-form policy fields. */
    public static JSONObject proposalProperties()throws Exception {
        JSONObject identity=object(new JSONObject().put("sourceId",text(80)).put("revision",integer(1,Long.MAX_VALUE)),"sourceId","revision");
        JSONObject resource=object(new JSONObject().put("path",text(160)).put("content",text(64000)),"path","content");
        JSONObject target=object(new JSONObject().put("id",text(80)).put("revision",integer(1,Integer.MAX_VALUE)).put("digest",text(64)),"id","revision","digest");
        return new JSONObject().put("action",new JSONObject().put("type","string").put("enum",new JSONArray().put("create").put("update")))
                .put("manifest",text(64000)).put("resources",new JSONObject().put("type","array").put("maxItems",40).put("items",resource))
                .put("sources",new JSONObject().put("type","array").put("maxItems",20).put("items",identity)).put("reason",text(1000)).put("target",target);
    }
    private static JSONObject object(JSONObject properties,String... required)throws Exception{return new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray(required)).put("additionalProperties",false);}
    private static JSONObject text(int max)throws Exception{return new JSONObject().put("type","string").put("maxLength",max);}
    private static JSONObject integer(long min,long max)throws Exception{return new JSONObject().put("type","integer").put("minimum",min).put("maximum",max);}
    public static JSONObject checkedDraft(JSONObject supplied)throws Exception {
        if(supplied==null||supplied.toString().length()>131072)throw new IllegalArgumentException("技能草稿超过原工具参数上限");Set<String> allowed=Set.of("action","manifest","resources","sources","reason","target");for(var keys=supplied.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("技能草稿不能携带权限、来源或宿主身份");
        JSONObject draft=new JSONObject(supplied.toString());String action=draft.getString("action");if(!Set.of("create","update").contains(action)||!(draft.opt("manifest") instanceof String)||!(draft.opt("reason") instanceof String)||draft.getString("reason").isBlank()||draft.getString("reason").length()>1000)throw new IllegalArgumentException("请明确技能草稿操作与修改理由");
        JSONArray resources=draft.getJSONArray("resources"),selected=draft.getJSONArray("sources");if(resources.length()>40||selected.length()<1||selected.length()>20)throw new IllegalArgumentException("技能草稿最多40个文本资源并选择1到20条真实主人来源");Set<String> paths=new HashSet<>(),refs=new HashSet<>();
        for(int i=0;i<resources.length();i++){JSONObject file=resources.getJSONObject(i);if(file.length()!=2||!(file.opt("path") instanceof String)||!(file.opt("content") instanceof String)||!paths.add(file.getString("path")))throw new SecurityException("技能资源必须是互不重复的path/content文本");}
        for(int i=0;i<selected.length();i++){JSONObject ref=selected.getJSONObject(i);if(ref.length()!=2||!(ref.opt("sourceId") instanceof String)||!dev.pocketengine.features.memory.TaskStoreMemoryQuery.validTaskId(ref.getString("sourceId"))||!refs.add(ref.getString("sourceId")+":"+positive(ref,"revision")))throw new SecurityException("请选择互不重复的准确主人消息来源");}
        if(action.equals("create")){if(draft.has("target"))throw new SecurityException("新技能不能携带覆盖目标");}
        else {JSONObject target=draft.getJSONObject("target");if(target.length()!=3||!target.optString("id").matches("[a-f0-9-]{36}")||!(target.opt("revision") instanceof Integer)||positive(target,"revision")>Integer.MAX_VALUE||!target.optString("digest").matches("[a-f0-9]{64}"))throw new SecurityException("更新必须绑定实际已读取的准确技能版本");}
        content(draft);return draft;
    }
    private static long positive(JSONObject row,String key)throws Exception{Object value=row.opt(key);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1)throw new IllegalArgumentException("修订必须为实际正整数");return ((Number)value).longValue();}
    private static JSONObject content(JSONObject draft)throws Exception{JSONObject resources=new JSONObject();JSONArray files=draft.getJSONArray("resources");for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);resources.put(file.getString("path"),file.getString("content"));}return new JSONObject().put("manifest",draft.getString("manifest")).put("resources",resources);}
    public static String proposalDigest(JSONObject draft)throws Exception{return SkillExecution.digestJson(checkedDraft(draft));}
    private static JSONObject origin(String task,String run,int epoch,String operation,String digest)throws Exception{return new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch).put("operationId",operation).put("proposalDigest",digest);}
    private JSONObject originalTask(String task,String run,int epoch)throws Exception{JSONObject row=tasks.get(task,false);if(row==null||!"assistant".equals(row.optString("kind"))||!"owner".equals(row.optString("actorId"))||!"private".equals(row.optString("spaceId"))||row.has("origin")||!run.equals(row.optString("runId"))||epoch!=row.optInt("coordinatorEpoch")||row.optString("conversationId").isBlank())throw new SecurityException("技能草稿不属于这个原私人对话执行");return row;}

    public JSONObject sourcesForTask(String task,String run,int epoch,RunControl original)throws Exception {
        if(original==null||!original.matchesHostRun(task,run,epoch))throw new SecurityException("来源发现缺少原任务控制器");tasks.checkCapability(task,run,epoch,"skills_draft_sources",original);JSONObject current=originalTask(task,run,epoch);
        JSONArray candidates=tasks.preparedConversationUserSources(task,run,epoch,current.getString("conversationId"));original.check();return new JSONObject().put("sources",candidates).put("candidateCoverage","currently_verified_prepared_parts_only").put("completeConversation",false).put("bodyIncluded",false).put("modelConsumptionConfirmed",false).put("executionAuthorized",false);
    }
    public JSONObject proposeForTask(String task,String run,int epoch,String operation,JSONObject supplied,RunControl original)throws Exception {
        JSONObject draft=checkedDraft(supplied);tasks.requireSkillProposalPrepared(task,run,epoch,operation,draft,original);JSONObject current=originalTask(task,run,epoch),scope=skills.taskScopeForHost(current);String conversation=current.getString("conversationId"),digest=proposalDigest(draft);JSONArray selected=draft.getJSONArray("sources"),refs=tasks.preparedConversationUserRefs(task,run,epoch,conversation,selected);
        JSONObject nativeOrigin=origin(task,run,epoch,operation,digest);JSONObject view=sources.withCurrent(refs,()->tasks.withSkillProposalSourceCurrent(task,run,epoch,conversation,selected,()->skills.inspectConversationDraft(scope,content(draft),draft.optJSONObject("target"),nativeOrigin).view()));
        if(draft.getString("action").equals("update"))tasks.requireSkillProposalTargetRead(task,run,epoch,view.getJSONObject("target"),view.getJSONArray("beforeFiles"));
        skills.requireTaskScopeForHost(tasks.get(task,false),scope);tasks.requireSkillProposalPrepared(task,run,epoch,operation,draft,original);
        JSONObject proposal=new JSONObject(nativeOrigin.toString()).put("format","pocketworld.conversation-skill-draft.v1").put("conversationId",conversation).put("action",draft.getString("action")).put("reason",draft.getString("reason"))
                .put("packageDigest",view.getJSONObject("package").getString("digest")).put("name",view.getJSONObject("package").getString("name")).put("target",view.getJSONObject("target")).put("targetStateDigest",view.getString("targetStateDigest")).put("skillScope",scope)
                .put("sourceRefs",refs).put("sourceCoverage","final_main_prepared_full_messages").put("status","pending_owner").put("savedAsIndependentSkill",true).put("formalSkillSaved",false).put("executionAuthorized",false);
        return new JSONObject().put("ok",true).put("skillDraft",proposal).put("reviewRequired",true).put("formalSkillSaved",false).put("executionAuthorized",false);
    }

    public Review reviewForOwner(String task,String run,int epoch,String operation,String expectedProposalDigest)throws Exception {
        JSONObject row=tasks.skillProposalForOwner(task,run,epoch,operation),proposal=row.getJSONObject("proposal"),draft=checkedDraft(row.getJSONObject("arguments"));
        if(!expectedProposalDigest.equals(proposal.getString("proposalDigest"))||!expectedProposalDigest.equals(proposalDigest(draft))||!"pending_owner".equals(row.getJSONObject("apply").getString("status")))throw new SecurityException("原技能草稿已变化或已提交，不能重新套用旧确认");
        JSONObject current=originalTask(task,run,epoch),scope=skills.taskScopeForHost(current);if(!SkillSourcePolicy.scopeFingerprint(scope).equals(SkillSourcePolicy.scopeFingerprint(proposal.getJSONObject("skillScope"))))throw new SecurityException("原任务技能范围已变化");String conversation=current.getString("conversationId");JSONArray selected=draft.getJSONArray("sources"),refs=tasks.preparedConversationUserRefs(task,run,epoch,conversation,selected);if(!SkillExecution.digestJson(new JSONObject().put("refs",refs)).equals(SkillExecution.digestJson(new JSONObject().put("refs",proposal.getJSONArray("sourceRefs")))))throw new SecurityException("原草稿用户来源已改变");
        SkillBook.SourceReview[] inspected={null};sources.withCurrent(refs,()->tasks.withSkillProposalSourceCurrent(task,run,epoch,conversation,selected,()->{inspected[0]=skills.inspectConversationDraft(scope,content(draft),draft.optJSONObject("target"),origin(task,run,epoch,operation,expectedProposalDigest));return inspected[0].view();}));
        JSONObject view=inspected[0].view();if(!proposal.getString("packageDigest").equals(view.getJSONObject("package").getString("digest"))||!proposal.getString("targetStateDigest").equals(view.getString("targetStateDigest")))throw new SecurityException("原覆盖对象内容、启停或策略已变化，请重新提案");
        if(draft.getString("action").equals("update"))tasks.requireSkillProposalTargetRead(task,run,epoch,view.getJSONObject("target"),view.getJSONArray("beforeFiles"));
        return new Review(task,run,epoch,operation,conversation,scope,selected,refs,proposal,inspected[0]);
    }

    public final class Review implements AutoCloseable {
        private final String task,run,operation,conversation,scope,selected,refs,proposal,view,reviewDigest;
        private final int epoch;
        private final SkillBook.SourceReview files;
        private final Map<String,List<int[]>> readRanges=new HashMap<>();
        private boolean closed,submitted;
        private Review(String task,String run,int epoch,String operation,String conversation,JSONObject scope,JSONArray selected,JSONArray refs,JSONObject proposal,SkillBook.SourceReview files)throws Exception {
            this.task=task;this.run=run;this.epoch=epoch;this.operation=operation;this.conversation=conversation;this.scope=scope.toString();this.selected=selected.toString();this.refs=refs.toString();this.proposal=proposal.toString();this.files=files;
            JSONObject presentation=files.view().put("proposal",new JSONObject(proposal.toString())).put("sourceRefs",refs).put("requiresAllFilePages",true).put("reviewRequired",true);
            reviewDigest=SkillExecution.digestJson(presentation);view=presentation.put("reviewDigest",reviewDigest).toString();
        }
        public synchronized JSONObject view()throws Exception{return new JSONObject(view).put("allFilePagesProvided",allFilesRead()).put("submitted",submitted).put("closed",closed);}
        private void live()throws Exception{if(closed||submitted||System.currentTimeMillis()>new JSONObject(view).getLong("expiresAt"))throw new SecurityException("此技能审阅已关闭、过期或已提交");JSONObject original=tasks.skillProposalForOwner(task,run,epoch,operation);if(!"pending_owner".equals(original.getJSONObject("apply").getString("status"))||!new JSONObject(proposal).getString("proposalDigest").equals(original.getJSONObject("proposal").getString("proposalDigest")))throw new SecurityException("原技能草稿已确认、取消或改变");skills.requireTaskScopeForHost(originalTask(task,run,epoch),new JSONObject(scope));}
        public synchronized JSONObject readFile(String side,String path,int offset,int length)throws Exception {
            live();JSONObject page=sources.withCurrent(new JSONArray(refs),()->tasks.withSkillProposalSourceCurrent(task,run,epoch,conversation,new JSONArray(selected),()->skills.readConversationDraftFile(new JSONObject(scope),files,side,path,offset,length)));
            String key=side+":"+path;List<int[]> ranges=new ArrayList<>(readRanges.getOrDefault(key,List.of()));ranges.add(new int[]{page.getInt("offset"),page.getInt("nextOffset")});ranges.sort(java.util.Comparator.comparingInt(value->value[0]));List<int[]> merged=new ArrayList<>();for(int[] range:ranges){if(merged.isEmpty()||range[0]>merged.get(merged.size()-1)[1])merged.add(range);else merged.get(merged.size()-1)[1]=Math.max(merged.get(merged.size()-1)[1],range[1]);}if(merged.size()>100)throw new IllegalArgumentException("请按页连续读取这份文件，审阅范围不能无限分散");readRanges.put(key,merged);return page.put("reviewDigest",reviewDigest).put("allFilePagesProvided",allFilesRead());
        }
        private boolean allFilesRead()throws Exception {
            JSONObject presentation=new JSONObject(view);for(String side:List.of("before","after")){JSONArray descriptors=presentation.getJSONArray(side+"Files");for(int i=0;i<descriptors.length();i++){JSONObject file=descriptors.getJSONObject(i);List<int[]> ranges=readRanges.get(side+":"+file.getString("path"));if(ranges==null)return false;List<int[]> sorted=new ArrayList<>(ranges);sorted.sort(java.util.Comparator.comparingInt(value->value[0]));int end=0;for(int[] range:sorted){if(range[0]>end)return false;end=Math.max(end,range[1]);}if(end<file.getInt("chars"))return false;}}return true;
        }
        public synchronized JSONObject confirm(RunControl original)throws Exception {
            live();original.check();if(!allFilesRead())throw new SecurityException("请先展开完整的原文件与候选文件，不能只凭摘要确认覆盖");JSONObject proposal=new JSONObject(this.proposal);String digest=proposal.getString("proposalDigest");
            if(!tasks.claimSkillProposalApplyForOwner(task,run,epoch,operation,digest,reviewDigest))throw new SecurityException("此原提案已提交，只能查询原保存结果");submitted=true;boolean[] entered={false};JSONObject result;
            try {
                JSONObject skill=sources.withCurrent(new JSONArray(refs),()->tasks.withSkillProposalSourceCurrent(task,run,epoch,conversation,new JSONArray(selected),()->{original.check();entered[0]=true;return skills.applyConversationDraft(new JSONObject(scope),files);}));
                result=savedReceipt(proposal,reviewDigest,skill);try{tasks.recordSkillProposalApplyForOwner(task,run,epoch,operation,reviewDigest,result);result.put("journalRecorded",true);}catch(Exception unknownReceipt){result.put("journalRecorded",false).put("queryOriginalReceipt",true);}
                try{skills.conversationDraftChanged();}catch(Exception presentationUnavailable){result.put("refreshRequired",true);}return result;
            } catch(Exception failure){
                boolean unknown=entered[0]&&files.conversationMutationEntered();result=receipt(proposal,reviewDigest,unknown?"unknown":"rejected").put("accepted",unknown?JSONObject.NULL:false).put("formalSkillSaved",unknown?JSONObject.NULL:false).put("message",unknown?"保存结果尚未完整确认；请按原任务和提案查询，不能再次提交":"原来源、技能版本或授权已变化，本次未进入技能保存；原技能保留");
                try{tasks.recordSkillProposalApplyForOwner(task,run,epoch,operation,reviewDigest,result);result.put("journalRecorded",true);}catch(Exception unknownReceipt){result.put("journalRecorded",false);}return result;
            }finally{closed=true;}
        }
        public synchronized JSONObject cancel()throws Exception{if(closed||submitted)throw new SecurityException("原审阅已关闭或提交");try{return cancelForOwner(task,run,epoch,operation,new JSONObject(proposal).getString("proposalDigest"));}finally{closed=true;}}
        @Override public synchronized void close(){closed=true;readRanges.clear();}
    }
    private static JSONObject receipt(JSONObject proposal,String reviewDigest,String status)throws Exception{return origin(proposal.getString("taskId"),proposal.getString("runId"),proposal.getInt("epoch"),proposal.getString("operationId"),proposal.getString("proposalDigest")).put("reviewDigest",reviewDigest).put("status",status).put("replayAllowed",false).put("executionAuthorized",false).put("savedAsIndependentSkill",true);}
    private static JSONObject savedReceipt(JSONObject proposal,String reviewDigest,JSONObject skill)throws Exception{return receipt(proposal,reviewDigest,"saved").put("accepted",true).put("formalSkillSaved",true).put("skill",new JSONObject().put("id",skill.getString("id")).put("revision",skill.getInt("revision")).put("digest",skill.getString("digest")).put("name",skill.getString("name")).put("enabled",skill.getBoolean("enabled"))).put("requiresSeparateEnable",!skill.getBoolean("enabled")).put("approvalGrantedByThisSave",false);}
    /** A stale/withdrawn proposal can still be explicitly discarded without reopening its files. */
    public JSONObject cancelForOwner(String task,String run,int epoch,String operation,String expectedProposalDigest)throws Exception{return tasks.cancelSkillProposalForOwner(task,run,epoch,operation,expectedProposalDigest);}
    /** Can reconcile a lost local ACK only from the original current package marker. Missing
     * or later-replaced content stays unknown and never authorizes a second import. */
    public JSONObject queryForOwner(String task,String run,int epoch,String operation)throws Exception {
        JSONObject row=tasks.skillProposalForOwner(task,run,epoch,operation),apply=row.getJSONObject("apply"),proposal=row.getJSONObject("proposal");String status=apply.getString("status");if(!Set.of("applying_unknown","unknown").contains(status))return row;
        JSONObject actual=skills.findConversationDraftResult(proposal.getJSONObject("skillScope"),origin(task,run,epoch,operation,proposal.getString("proposalDigest")),proposal.getString("packageDigest"));
        if(actual.optBoolean("found")){String reviewed=apply.getString("reviewDigest");JSONObject settled=savedReceipt(proposal,reviewed,actual.getJSONObject("skill")).put("observedFromCurrentPackage",true);tasks.recordSkillProposalApplyForOwner(task,run,epoch,operation,reviewed,settled);return tasks.skillProposalForOwner(task,run,epoch,operation);}
        return row.put("replayAllowed",false).put("packageReadbackStatus","original_saved_version_not_currently_provable");
    }
}
