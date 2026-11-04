package dev.pocketengine.features.memory;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.KnowledgeLearningWorkflow;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.TaskStore;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded native workflow over the original Task/Run/Operation journal, not a scheduler,
 * a background agent or another job database. One source is one resumable candidate batch. */
public final class SourceLearningJob {
    public static final String TOOL="native_memory_learning_batch",FORMAT="pocketworld.source-learning-job.v1";
    public static final int MAX_INPUTS=20,CALLS_PER_ATTEMPT=3;
    private final TaskStore tasks;private final String task,run;private final int epoch;private final RunControl control;
    private final KnowledgeLearningWorkflow learning;private final Callable<InferenceReceipt> actualReceipt;
    private final Callable<Void> synchronize;private final int maxBatches;private final SourcePreparation prepareSource;
    /** Native IO only, before the selected batch reads source bytes; never a source grant. */
    public interface SourcePreparation {void prepare(SourceRef source,RunControl original)throws Exception;}
    public SourceLearningJob(TaskStore tasks,String task,String run,int epoch,RunControl control,KnowledgeLearningWorkflow learning,Callable<InferenceReceipt> actualReceipt,Callable<Void> synchronize){
        this(tasks,task,run,epoch,control,learning,actualReceipt,synchronize,CALLS_PER_ATTEMPT);
    }
    public SourceLearningJob(TaskStore tasks,String task,String run,int epoch,RunControl control,KnowledgeLearningWorkflow learning,Callable<InferenceReceipt> actualReceipt,Callable<Void> synchronize,int maxBatches){
        this(tasks,task,run,epoch,control,learning,actualReceipt,synchronize,maxBatches,(source,original)->{});
    }
    public SourceLearningJob(TaskStore tasks,String task,String run,int epoch,RunControl control,KnowledgeLearningWorkflow learning,Callable<InferenceReceipt> actualReceipt,Callable<Void> synchronize,int maxBatches,SourcePreparation prepareSource){
        if(maxBatches<1||maxBatches>MAX_INPUTS)throw new IllegalArgumentException("本次原来源提炼须为1至20批");
        this.tasks=java.util.Objects.requireNonNull(tasks);this.task=task;this.run=run;this.epoch=epoch;this.control=java.util.Objects.requireNonNull(control);this.learning=java.util.Objects.requireNonNull(learning);this.actualReceipt=java.util.Objects.requireNonNull(actualReceipt);this.synchronize=java.util.Objects.requireNonNull(synchronize);this.maxBatches=maxBatches;this.prepareSource=java.util.Objects.requireNonNull(prepareSource);
    }
    /** Native manifests contain identities, hashes and display labels only, never original bodies. */
    public static JSONArray checkedInputs(JSONArray input)throws Exception{
        if(input==null||input.length()<1||input.length()>MAX_INPUTS)throw new IllegalArgumentException("逐批提炼请选择1至20份精确来源");JSONArray result=new JSONArray();Set<SourceRef> seen=new HashSet<>();
        for(int i=0;i<input.length();i++){JSONObject row=input.getJSONObject(i);if(row.length()!=3)throw new SecurityException("来源清单只接受native引用、内容指纹和标题");for(var keys=row.keys();keys.hasNext();)if(!Set.of("sourceRef","sourceFingerprint","title").contains(keys.next()))throw new SecurityException("来源清单不能带正文或授权");SourceRef ref=SourceRef.fromJson(row.getJSONObject("sourceRef"));String fingerprint=row.getString("sourceFingerprint"),title=row.getString("title");
            if(!"owner".equals(ref.ownerId)||!"private".equals(ref.spaceId)||!seen.add(ref)||!fingerprint.matches("sha256:[0-9a-f]{64}")||title.isEmpty()||title.length()>160)throw new SecurityException("来源清单身份、版本或指纹无效/重复");result.put(new JSONObject().put("sourceRef",ref.toJson()).put("sourceFingerprint",fingerprint).put("title",title));
        }return result;
    }
    /** Native owner dispatcher; never registered in EngineModule/plugin/model tool catalogs. */
    public interface OwnerCommands {
        JSONObject create(JSONArray sources)throws Exception;JSONObject add(String task,long revision,SourceRef source)throws Exception;
        JSONObject get(String task)throws Exception;JSONObject list(String cursor,int limit)throws Exception;
        JSONObject run(String task,long revision,RunControl original)throws Exception;
        default JSONObject run(String task,long revision,RunControl original,int maxBatches)throws Exception{if(maxBatches!=CALLS_PER_ATTEMPT)throw new IllegalStateException("原宿主不支持这个连续批次窗口");return run(task,revision,original);}
        JSONObject skip(String task,long revision,boolean confirmed)throws Exception;JSONObject stop(String task,String run,int epoch)throws Exception;
    }
    public static JSONObject dispatch(String command,JSONObject payload,RunControl original,OwnerCommands host)throws Exception{
        java.util.Objects.requireNonNull(host);java.util.Objects.requireNonNull(original);java.util.Set<String> allowed=switch(command){case "createLearningJob"->java.util.Set.of("sourceRefs");case "addLearningJobSource"->java.util.Set.of("taskId","revision","sourceRef");case "getLearningJob"->java.util.Set.of("taskId");case "stopLearningJob"->java.util.Set.of("taskId","runId","epoch");case "listLearningJobs"->java.util.Set.of("cursor","limit");case "runLearningJob"->java.util.Set.of("taskId","revision","confirmModelUse","maxBatches");case "skipLearningJobBatch"->java.util.Set.of("taskId","revision","confirmUnknownUse");default->throw new IllegalArgumentException("不是原逐批来源学习操作");};
        if(payload==null||payload.length()<(command.equals("runLearningJob")?allowed.size()-1:allowed.size())||payload.length()>allowed.size())throw new SecurityException("逐批来源任务参数不完整或含替代字段");for(var keys=payload.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("逐批来源任务不能覆盖模型、身份、回执或授权");original.check();
        return switch(command){
            case "createLearningJob"->host.create(payload.getJSONArray("sourceRefs"));
            case "addLearningJobSource"->host.add(payload.getString("taskId"),positiveInteger(payload,"revision"),SourceRef.fromJson(payload.getJSONObject("sourceRef")));
            case "getLearningJob"->host.get(payload.getString("taskId"));case "stopLearningJob"->{
                Object run=payload.opt("runId"),epoch=payload.opt("epoch");if(!(run instanceof String)||!(epoch instanceof Integer||epoch instanceof Long)||((Number)epoch).longValue()<0||((Number)epoch).longValue()>Integer.MAX_VALUE||((String)run).isEmpty()!=(((Number)epoch).longValue()==0))throw new SecurityException("停止须携带所读取的原学习执行身份，不能临时借用当前执行");
                yield host.stop(payload.getString("taskId"),(String)run,((Number)epoch).intValue());
            }
            case "listLearningJobs"->host.list(payload.getString("cursor"),Math.toIntExact(positiveInteger(payload,"limit")));
            case "runLearningJob"->{if(!Boolean.TRUE.equals(payload.opt("confirmModelUse")))throw new SecurityException("须由主人明确确认本次把所选来源发给当前模型");int max=payload.has("maxBatches")?Math.toIntExact(positiveInteger(payload,"maxBatches")):CALLS_PER_ATTEMPT;if(max>MAX_INPUTS)throw new IllegalArgumentException("本次最多20批，不能扩展原来源清单");yield host.run(payload.getString("taskId"),positiveInteger(payload,"revision"),original,max);}
            default->{if(!Boolean.TRUE.equals(payload.opt("confirmUnknownUse")))throw new SecurityException("须明确确认跳过，未知调用不退款");yield host.skip(payload.getString("taskId"),positiveInteger(payload,"revision"),true);}
        };
    }
    private static long positiveInteger(JSONObject value,String key){Object raw=value.opt(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<1)throw new IllegalArgumentException(key+"须为实际正整数");return ((Number)raw).longValue();}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    /** A native owner explicitly starts each short invocation. Finished sources are never sent
     * again. A recorded draft can be replayed only into MemoryBook's existing idempotent gate. */
    public JSONObject run()throws Exception{
        int calls=0;
        while(true){
            control.check();JSONObject snapshot=tasks.sourceLearningJob(task),job=snapshot.getJSONObject("job");int index=job.getInt("nextIndex");JSONArray inputs=job.getJSONArray("inputs");
            if(index>=inputs.length())return snapshot.put("invocationOutcome","all_batches_settled").put("gatewayInvocationsThisAttempt",calls);
            JSONObject active=snapshot.optJSONObject("active"),recorded;
            if(active!=null&&!"draft_recorded".equals(active.optString("stage")))return snapshot.put("invocationOutcome","unknown_model_result_requires_owner_review").put("gatewayInvocationsThisAttempt",calls);
            if(active==null&&calls>=maxBatches)return snapshot.put("invocationOutcome","batch_window_complete_continue_explicitly").put("gatewayInvocationsThisAttempt",calls);
            JSONObject input=inputs.getJSONObject(index);SourceRef ref=SourceRef.fromJson(input.getJSONObject("sourceRef"));
            // Prepare only the actual current source, outside Tasks/Book locks. A future deleted
            // input must not block this bounded batch, and cached drafts still recheck cold bytes.
            prepareSource.prepare(ref,control);control.check();synchronize.call();control.check();
            if(active!=null){
                recorded=tasks.sourceLearningDraft(task,active.getString("operationId"));
            }else{
                // This pre-read avoids reserving a new model attempt for an already invalid source.
                JSONObject contract=learning.sourceLearningContract(scope(),ref,input.getString("sourceFingerprint"),control);
                active=tasks.beginSourceLearningBatch(task,run,epoch,index,control,contract);String operation=active.getString("operationId");
                try{
                    recorded=learning.extractRecordedLearning(scope(),ref,input.getString("sourceFingerprint"),control);calls++;
                    InferenceReceipt receipt=actualReceipt.call();
                    if(receipt==null||receipt.outcome!=InferenceReceipt.Outcome.COMPLETED)throw new IllegalStateException("真实模型终态回执未确认，未把draft当已完成");
                    tasks.recordSourceLearningDraft(task,run,epoch,operation,recorded,receipt,control);
                }catch(Exception failed){try{tasks.markSourceLearningUnknown(task,run,epoch,operation);}catch(Exception cleanup){failed.addSuppressed(cleanup);}throw failed;}
            }
            control.check();JSONObject candidate=learning.publishRecordedLearning(scope(),recorded,control);control.check();
            tasks.completeSourceLearningBatch(task,run,epoch,active.getString("operationId"),candidate,control);
        }
    }
}
