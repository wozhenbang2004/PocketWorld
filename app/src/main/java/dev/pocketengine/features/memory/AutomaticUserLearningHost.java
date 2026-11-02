package dev.pocketengine.features.memory;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.KnowledgeLearningWorkflow;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.store.TaskStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native successful-task/idle glue over the original SourceLearningJob and TaskStore.
 * Owns no worker, controller, model, timer, retry loop or queue. */
public final class AutomaticUserLearningHost {
    /** Original Engine admission only. A busy return leaves this SAME original queued job.
     * It must call the existing runSourceLearningJob with contextForOriginalJob below installed. */
    @FunctionalInterface public interface OriginalJobAdmission {
        JSONObject admit(String originalLearningTask,long expectedRevision)throws Exception;
    }
    private final TaskStore tasks;
    private final MemoryModule memory;
    private final AssistantModule assistant;
    private final AutomaticMemoryPolicy policy;
    private final OriginalJobAdmission originalJobs;
    private String idleCursor="";
    private boolean idleMayWrap;
    public AutomaticUserLearningHost(TaskStore tasks,MemoryModule memory,AssistantModule assistant,OriginalJobAdmission originalJobs){
        this.tasks=Objects.requireNonNull(tasks);this.memory=Objects.requireNonNull(memory);this.assistant=Objects.requireNonNull(assistant);
        this.policy=memory.automaticLearningPolicyForHost();this.originalJobs=Objects.requireNonNull(originalJobs);
    }
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class SourceTask {
        final String task,run,conversation;final int epoch;
        SourceTask(JSONObject actual)throws Exception{task=actual.getString("id");run=actual.getString("runId");epoch=actual.getInt("coordinatorEpoch");conversation=actual.getString("conversationId");}
        boolean same(JSONObject actual){return eligible(actual)&&task.equals(actual.optString("id"))&&run.equals(actual.optString("runId"))&&epoch==actual.optInt("coordinatorEpoch")&&conversation.equals(actual.optString("conversationId"));}
    }
    private static boolean eligible(JSONObject task){return task!=null&&"owner".equals(task.optString("actorId"))&&"private".equals(task.optString("spaceId"))&&"completed".equals(task.optString("status"))&&!task.has("origin")&&!task.has("sourceLearningJob")&&!task.optString("conversationId").isBlank()&&task.optString("runId").matches("[a-f0-9-]{36}")&&task.optInt("coordinatorEpoch")>0;}
    private KnowledgeLearningWorkflow.SourceDocument readUser(SourceTask source,SourceRef ref)throws Exception{
        if(!"message".equals(ref.kind)||!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId)||memory.isSourceForgotten(scope(),ref))throw new SecurityException("自动原话来源已撤回或不在私人主人范围");
        var document=assistant.readAutomaticOwnerUtterance(ref,source.task,source.run,source.epoch);
        if(!ref.equals(document.ref)||memory.isSourceForgotten(scope(),ref))throw new SecurityException("自动原话来源在读取时变化或已遗忘");return document;
    }
    /** Called on the original native task event worker, outside Tasks/Book locks.
     * The original event fence serializes settings changes and final native job registration. */
    public JSONObject onSuccessfulTask(String originalTaskId,MemoryHostFence eventFence)throws Exception{
        Objects.requireNonNull(eventFence);if(Thread.holdsLock(tasks))throw new IllegalStateException("原话读取必须在原任务提交锁外");eventFence.check();
        JSONObject selected=policy.snapshot();if(!selected.getBoolean("enabled"))return outcome("automatic_mode_disabled");
        JSONObject actual=tasks.get(originalTaskId,false);if(!eligible(actual))return outcome("not_successful_original_owner_task");SourceTask source=new SourceTask(actual);
        int maximum=selected.getInt("maxUserSourcesPerTask");JSONArray adopted=actual.optJSONArray("ownerInputs"),inputs=new JSONArray();Map<SourceRef,Boolean> seen=new LinkedHashMap<>();int skipped=0;
        // Newest adopted user messages first. Bound local source IO even with a long retained mailbox.
        for(int i=adopted==null?-1:adopted.length()-1,inspected=0;i>=0&&inputs.length()<maximum&&inspected<20;i--,inspected++){
            eventFence.check();policy.requireCurrent(selected);JSONObject row=adopted.getJSONObject(i);Object revision=row.opt("ownerMessageRevision");
            if(!source.task.equals(row.optString("acceptedTaskId"))||!"materialized".equals(row.optString("queueState"))||!source.run.equals(row.optString("materializedRunId"))||source.epoch!=row.optInt("materializedEpoch")||!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1)continue;
            try{SourceRef ref=new SourceRef("message",row.getString("messageId"),((Number)revision).longValue(),"owner","private");if(seen.put(ref,true)!=null)continue;
                var document=readUser(source,ref);inputs.put(new JSONObject().put("sourceRef",ref.toJson()).put("sourceFingerprint",KnowledgeLearningWorkflow.sourceFingerprint(document)).put("title",document.title));
            }catch(SecurityException|IllegalArgumentException|IllegalStateException unavailable){skipped++;}
        }
        if(inputs.length()==0)return outcome("no_current_user_sources").put("skippedSources",skipped);
        JSONObject registered=eventFence.admit(()->{policy.requireCurrent(selected);requireSources(source,inputs);return tasks.ensureAutomaticUserLearningJob(source.task,source.run,source.epoch,selected,inputs);});
        // Admission may run a model only through the original Engine after its own current fence.
        JSONObject admission=admitUnstartedOriginalJob(registered.getString("taskId"),eventFence);
        return registered.put("automaticAdmission",admission).put("selectedUserSources",inputs.length()).put("skippedSources",skipped).put("formalMemorySaved",false);
    }
    private void requireSources(SourceTask source,JSONArray inputs)throws Exception{
        if(!source.same(tasks.get(source.task,false)))throw new SecurityException("原成功任务的执行或对话已变化");
        for(int i=0;i<inputs.length();i++){JSONObject input=inputs.getJSONObject(i);SourceRef ref=SourceRef.fromJson(input.getJSONObject("sourceRef"));if(!input.getString("sourceFingerprint").equals(KnowledgeLearningWorkflow.sourceFingerprint(readUser(source,ref))))throw new SecurityException("原话版本或正文已变化，未发送旧提炼来源");}
    }
    /** Existing native idle/recovery can retry ONLY original unstarted queued admission.
     * Started, inflight, recorded-draft and unknown jobs are never automatically resumed here. */
    public JSONObject admitUnstartedOriginalJob(String taskId,MemoryHostFence eventFence)throws Exception{
        JSONObject ready=readyOriginalJob(taskId,eventFence);
        if(!ready.optBoolean("ready"))return ready;eventFence.check();return Objects.requireNonNull(originalJobs.admit(ready.getString("taskId"),ready.getLong("revision")),"original_learning_admission_receipt_missing");
    }
    /** Original Engine pickup boundary, under its same native admission monitor. */
    public void requireUnstartedOriginalJob(String taskId,long expectedRevision,MemoryHostFence eventFence)throws Exception{
        JSONObject ready=readyOriginalJob(taskId,eventFence);if(!ready.optBoolean("ready")||ready.getLong("revision")!=expectedRevision)throw new SecurityException("原自动原话批次已停止、开始或变化，排队唤醒不能重新授权");
    }
    private JSONObject readyOriginalJob(String taskId,MemoryHostFence eventFence)throws Exception{
        Objects.requireNonNull(eventFence);return eventFence.admit(()->{
            JSONObject snapshot=tasks.sourceLearningJob(taskId),job=snapshot.getJSONObject("job"),binding=job.optJSONObject("automaticUserSource");
            if(binding==null||job.optBoolean("started")||job.optJSONObject("active")!=null||!"queued".equals(snapshot.optString("taskStatus")))return outcome("original_job_not_unstarted_queued");
            JSONObject selected=binding.getJSONObject("selectedPolicy");policy.requireCurrent(selected);SourceTask source=sourceBinding(binding);requireSources(source,job.getJSONArray("inputs"));
            return new JSONObject().put("taskId",taskId).put("revision",job.getLong("revision")).put("ready",true);
        });
    }
    /** Start a real native wake, retaining its selected page and permitting one wrap to newer rows. */
    public synchronized void beginOriginalIdleScan(){idleMayWrap=!idleCursor.isEmpty();}
    /** One bounded read of the ORIGINAL TaskStore index on an existing Engine idle event.
     * No timer or retained execution queue; at most one original queued admission per event. */
    public JSONObject onOriginalIdle(MemoryHostFence eventFence)throws Exception{
        Objects.requireNonNull(eventFence);eventFence.check();if(!policy.snapshot().getBoolean("enabled"))return outcome("automatic_mode_disabled");String cursor;synchronized(this){cursor=idleCursor;}
        JSONObject page=tasks.listSourceLearningJobs(cursor,20);
        JSONArray jobs=page.getJSONArray("jobs");java.util.List<JSONObject> queued=new java.util.ArrayList<>();for(int i=0;i<jobs.length();i++){JSONObject snapshot=jobs.getJSONObject(i),job=snapshot.getJSONObject("job");if(job.optJSONObject("automaticUserSource")!=null&&!job.optBoolean("started")&&job.optJSONObject("active")==null&&"queued".equals(snapshot.optString("taskStatus")))queued.add(snapshot);}
        queued.sort(java.util.Comparator.comparingLong(value->value.optLong("createdAt")));
        for(JSONObject snapshot:queued){JSONObject ready;
            try{ready=readyOriginalJob(snapshot.getString("taskId"),eventFence);}catch(SecurityException|IllegalStateException noLongerCurrent){continue;}
            if(!ready.optBoolean("ready"))continue;
            // Preserve this original page before native admission, including a busy/unknown ACK.
            // Only an actual later idle/release may look for the other queued rows on this page.
            eventFence.check();JSONObject admitted=Objects.requireNonNull(originalJobs.admit(ready.getString("taskId"),ready.getLong("revision")),"original_learning_admission_receipt_missing");
            return admitted.put("hasMoreNativeMetadata",false).put("originalMetadataPageRetained",true);
        }
        boolean more=page.optBoolean("hasMore");synchronized(this){if(idleCursor.equals(cursor)){idleCursor=more?page.getString("nextCursor"):"";if(!more&&idleMayWrap){idleMayWrap=false;more=true;}}}
        return outcome("no_current_unstarted_automatic_job").put("hasMoreNativeMetadata",more);
    }
    private SourceTask sourceBinding(JSONObject binding)throws Exception{
        JSONObject actual=tasks.get(binding.getString("taskId"),false);if(!eligible(actual))throw new SecurityException("自动提炼原任务不再有效");SourceTask source=new SourceTask(actual);
        if(!source.run.equals(binding.getString("runId"))||source.epoch!=binding.getInt("epoch")||!source.conversation.equals(binding.getString("conversationId")))throw new SecurityException("自动提炼来源执行身份已变化");return source;
    }
    /** The real Engine passes its retained LEARNING run, separately from the completed source run. */
    public KnowledgeLearningWorkflow.AutomaticUserContext contextForOriginalJob(String taskId,String learningRun,int learningEpoch,RunControl original)throws Exception{
        JSONObject snapshot=tasks.sourceLearningJob(taskId),job=snapshot.getJSONObject("job"),binding=job.optJSONObject("automaticUserSource");if(binding==null)return null;
        checkLearningRun(taskId,learningRun,learningEpoch,original);JSONObject selected=binding.getJSONObject("selectedPolicy");policy.requireCurrent(selected);SourceTask source=sourceBinding(binding);
        JSONArray inputs=SourceLearningJob.checkedInputs(job.getJSONArray("inputs"));if(inputs.length()>selected.getInt("maxUserSourcesPerTask"))throw new SecurityException("自动来源清单超过主人窗口");Map<SourceRef,String> allowed=new LinkedHashMap<>();for(int i=0;i<inputs.length();i++){JSONObject input=inputs.getJSONObject(i);allowed.put(SourceRef.fromJson(input.getJSONObject("sourceRef")),input.getString("sourceFingerprint"));}
        return new KnowledgeLearningWorkflow.AutomaticUserContext(source.task,(expectedTask,ref,control)->{
            if(control!=original||!source.task.equals(expectedTask)||!allowed.containsKey(ref))throw new SecurityException("自动来源或控制器不是原学习批次");checkLearningRun(taskId,learningRun,learningEpoch,original);policy.requireCurrent(selected);
            if(!allowed.get(ref).equals(KnowledgeLearningWorkflow.sourceFingerprint(readUser(source,ref))))throw new SecurityException("自动原话正文已变化");
        },policy,selected);
    }
    /** Original ModelGateway dispatch-admission hook: check again at the real send boundary. */
    public void requireCurrentOriginalJobSource(String taskId,String learningRun,int learningEpoch,SourceRef ref,RunControl original)throws Exception{
        checkLearningRun(taskId,learningRun,learningEpoch,original);JSONObject job=tasks.sourceLearningJob(taskId).getJSONObject("job"),binding=job.optJSONObject("automaticUserSource");if(binding==null)throw new SecurityException("不是原自动原话学习任务");
        JSONObject selected=binding.getJSONObject("selectedPolicy");policy.requireCurrent(selected);JSONArray inputs=SourceLearningJob.checkedInputs(job.getJSONArray("inputs"));if(inputs.length()>selected.getInt("maxUserSourcesPerTask"))throw new SecurityException("自动来源清单超出原窗口");
        String fingerprint=null;for(int i=0;i<inputs.length();i++){JSONObject input=inputs.getJSONObject(i);if(ref.equals(SourceRef.fromJson(input.getJSONObject("sourceRef"))))fingerprint=input.getString("sourceFingerprint");}
        if(fingerprint==null||!fingerprint.equals(KnowledgeLearningWorkflow.sourceFingerprint(readUser(sourceBinding(binding),ref))))throw new SecurityException("自动原话不在当前原批次或正文已变化");policy.requireCurrent(selected);checkLearningRun(taskId,learningRun,learningEpoch,original);
    }
    private void checkLearningRun(String taskId,String run,int epoch,RunControl original)throws Exception{
        if(original==null||!original.matchesHostRun(taskId,run,epoch)||original.isPaused())throw new SecurityException("缺少原学习任务保留的当前控制器");original.check();JSONObject current=tasks.sourceLearningJob(taskId);
        if(!run.equals(current.optString("runId"))||epoch!=current.optInt("epoch")||!current.optString("taskStatus").matches("running|preparing|planning|acting|observing"))throw new SecurityException("原学习任务运行已结束或变化");
    }
    private static JSONObject outcome(String code)throws Exception{return new JSONObject().put("outcome",code).put("formalMemorySaved",false).put("automaticModelRetry",false);}
}
