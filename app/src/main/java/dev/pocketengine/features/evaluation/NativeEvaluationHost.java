package dev.pocketengine.features.evaluation;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.InferenceRouteContinuity;
import dev.pocketengine.runtime.models.ModelRoutePlan;
import dev.pocketengine.store.TaskStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner review and independent observation around the existing task executor, never a Runner. */
public final class NativeEvaluationHost {
    public interface Models { ModelConfig selected()throws Exception;ModelConfig current(ModelConfig captured)throws Exception; }
    public interface Completion { void finished(Exception failure); }
    public interface Executor { void submit(String task,ModelConfig captured,RunControl original,Completion complete)throws Exception; }
    private final TaskStore tasks;
    private final Path root;
    private final Models models;
    private final Executor executor;
    private final Map<String,Active> active=new ConcurrentHashMap<>();
    private final Map<String,ModelConfig> capturedStudies=new ConcurrentHashMap<>();
    private static final class Active {
        final ModelConfig model;final RunControl original;final JSONObject task;
        Active(ModelConfig model,RunControl control,JSONObject task){this.model=model;original=control;this.task=task;}
    }
    private static final class Review {
        final String id=UUID.randomUUID().toString();final JSONObject trial,study;final ModelConfig model;
        Review(JSONObject trial,JSONObject study,ModelConfig model){this.trial=trial;this.study=study;this.model=model;}
    }
    public NativeEvaluationHost(TaskStore tasks,Path root,Models models,Executor executor){this.tasks=tasks;this.root=root;this.models=models;this.executor=executor;}
    public Page openPage(){return new Page();}

    /** Retained native owner window. Closing it invalidates pending reviews, not completed facts. */
    public final class Page implements AutoCloseable {
        private boolean closed;private Review review;
        private synchronized void check(){if(closed)throw new SecurityException("原评测页面已离开");}
        @Override public synchronized void close(){closed=true;review=null;}
        public synchronized JSONObject command(String command,JSONObject input)throws Exception{
            check();if(input==null)input=new JSONObject();
            switch(command){
                case "snapshot":return snapshot(input.optString("studyId"));
                case "create":{
                    ModelConfig model=exactMain(models.selected());JSONObject study=tasks.createEvaluationStudyForOwner(modelFacts(model));capturedStudies.put(study.getString("id"),model);review=null;return snapshot(study.getString("id"));
                }
                case "review":{
                    JSONObject study=tasks.evaluationStudyForOwner(input.getString("studyId"));ModelConfig model=modelForStudy(study);
                    JSONObject trial=EvaluationDataset.trial(study.getString("id"),input.getString("caseId"),input.getString("strategy"));
                    if(!study.getString("datasetSha256").equals(trial.getString("datasetSha256")))throw new SecurityException("数据集版本已变化，请另建实验");
                    if("holdout".equals(trial.getString("pool"))&&!trial.getString("strategy").equals(study.getString("frozenStrategy")))throw new SecurityException("先冻结调参候选，再运行保留池");
                    if("tune".equals(trial.getString("pool"))&&!study.getString("frozenStrategy").isEmpty())throw new SecurityException("已冻结实验不再调参");
                    review=new Review(trial,study,model);return new JSONObject().put("reviewId",review.id).put("trial",trial).put("model",study.getJSONObject("model")).put("maximumModelRequests",EvaluationDataset.MODEL_LIMIT).put("syntheticOnly",true).put("externalAppsAllowed",false);
                }
                case "start":{
                    if(review==null||!review.id.equals(input.optString("reviewId"))||!Boolean.TRUE.equals(input.opt("confirmed")))throw new SecurityException("请先明确确认当前评测范围和模型额度");
                    JSONObject started=start(review);return snapshot(review.study.getString("id")).put("startedTaskId",started.getString("id")).put("knownEvaluation",started.optBoolean("knownEvaluation"));
                }
                case "freeze":review=null;tasks.freezeEvaluationStrategyForOwner(input.getString("studyId"),input.getLong("revision"),input.getString("strategy"));return snapshot(input.getString("studyId"));
                case "cancel":{
                    String id=input.getString("taskId");JSONObject task=evaluationTask(id);Active execution=active.get(id);
                    if(execution!=null){tasks.requestCancel(id,task.getString("runId"));execution.original.cancel();}return snapshot(task.getJSONObject("evaluation").getJSONObject("trial").getString("studyId"));
                }
                case "probe":{
                    String id=input.getString("taskId");JSONObject task=evaluationTask(id);if(active.containsKey(id))throw new SecurityException("原执行仍在进行；先停止或等待原回执，不重放试验");
                    recordProbe(id,"");return snapshot(task.getJSONObject("evaluation").getJSONObject("trial").getString("studyId"));
                }
                case "compare":return compare(input.getString("beforeStudyId"),input.getString("afterStudyId"));
                default:throw new IllegalArgumentException("未知原生评测命令");
            }
        }
    }
    private synchronized JSONObject start(Review review)throws Exception{
        String id=EvaluationDataset.taskId(review.trial);JSONObject already=tasks.get(id,false);if(already!=null){evaluationTask(id);return already.put("knownEvaluation",true);}
        if(!active.isEmpty())throw new IllegalStateException("已有合成试验正在执行；先等待原试验，不叠加预算");
        JSONObject currentStudy=tasks.evaluationStudyForOwner(review.study.getString("id"));
        if(currentStudy.getLong("revision")!=review.study.getLong("revision"))throw new SecurityException("原实验修订已变化，请重新审阅");
        ModelConfig current=modelForStudy(currentStudy);InferenceRouteContinuity.requireCurrent(review.model,current,new RunControl());
        SyntheticEvaluationPort.initialize(root,id,review.trial);RunControl original=new RunControl();JSONObject task=tasks.createEvaluationTaskForOwner(review.trial,original);
        if(task.optBoolean("knownEvaluation"))return task;
        Active own=new Active(review.model,original,task);active.put(id,own);
        original.setAuthorityCheck(()->requireCurrent(id,own));
        try{executor.submit(id,own.model,original,failure->settled(id,own,failure));}
        catch(Exception rejected){tasks.endAttempt(id,task.getString("runId"),"failed","合成评测未入队；原试验保留，不自动重试",new JSONObject());settled(id,own,rejected);throw rejected;}
        return task;
    }
    private void requireCurrent(String id,Active own)throws Exception{
        if(active.get(id)!=own)throw new SecurityException("原评测执行器已变化");
        // Avoid recursion: RunControl.check invokes this authority callback.
        ModelConfig current=exactMain(models.current(own.model));if(!modelFacts(own.model).toString().equals(modelFacts(current).toString()))throw new SecurityException("原评测模型来源或配置修订已变化");
        if(!own.model.sameCredentials(current))throw new SecurityException("原评测凭据已变化");
    }
    public CapabilityPort capability(String id,RunControl original)throws Exception{
        Active own=active.get(id);if(own==null||own.original!=original)throw new SecurityException("原合成试验控制器不可恢复或另建");requireCurrent(id,own);
        return new SyntheticEvaluationPort(tasks,root,tasks.get(id,false),original,()->requireCurrent(id,own));
    }
    private void settled(String id,Active own,Exception failure){
        try{
            if(active.get(id)!=own)return;JSONObject task=evaluationTask(id);
            if(task.optString("status").matches("preparing|running|stopping"))tasks.endAttempt(id,own.task.getString("runId"),own.original.isCancelled()?"cancelled":"failed","原合成执行已结束；按真实回执独立核对",new JSONObject());
            recordProbe(id,failure==null?"":"native_execution_unavailable");
        }catch(Exception unavailable){/* No substitute result or replay: original task audit remains. */}
        finally{active.remove(id,own);}
    }
    private JSONObject evaluationTask(String id)throws Exception{
        EvaluationDataset.uuid(id);JSONObject task=tasks.get(id,false);
        if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.optJSONObject("evaluation")==null)throw new SecurityException("不是原主人合成评测任务");return task;
    }
    private void recordProbe(String id,String failure)throws Exception{
        JSONObject task=evaluationTask(id),previous=task.getJSONObject("evaluation").optJSONObject("probe");
        if((failure==null||failure.isEmpty())&&previous!=null)failure=previous.optString("nativeExecutionFailure");
        JSONObject probe=EvaluationEvidence.probe(root,task,failure);
        tasks.recordEvaluationProbe(id,task.getString("runId"),task.getInt("coordinatorEpoch"),probe);
    }
    private ModelConfig modelForStudy(JSONObject study)throws Exception{
        ModelConfig captured=capturedStudies.get(study.getString("id")),selected=exactMain(captured==null?models.selected():models.current(captured));
        if(captured==null&&selected.capabilityBinding==null&&!selected.usesAccount())throw new SecurityException("旧兼容连接没有持久修订证明，请创建新实验；未重用旧评测权限");
        if(!study.getJSONObject("model").toString().equals(modelFacts(selected).toString()))throw new SecurityException("当前连接与原实验模型身份不同，请选择原连接或创建独立实验");
        if(captured!=null)InferenceRouteContinuity.requireCurrent(captured,selected,new RunControl());return selected;
    }
    /** Pin the already selected MAIN model, retaining its native credential/catalog/configuration.
     * Bounded strategy comparisons must not silently change models or use a SUMMARY target. */
    private static ModelConfig exactMain(ModelConfig selected){
        ModelConfig main=selected.forPurpose("MAIN").withRoutePlan(null).withPurposePlans(Map.of());
        return main.withRoutePlan(ModelRoutePlan.exact(main,"MAIN","APP_OWNER"));
    }
    public static JSONObject modelFacts(ModelConfig model)throws Exception{
        return new JSONObject().put("provider",model.provider).put("model",model.model).put("configurationDigest",ModelRoutePlan.configurationDigest(model))
                .put("executionRoute","exact_main").put("apiBinding",model.capabilityBinding==null?JSONObject.NULL:model.capabilityBinding.toJson()).put("accountBinding",model.usesAccount()?model.accountModel.toJson():JSONObject.NULL);
    }
    public JSONObject snapshot(String selected)throws Exception{
        JSONArray studies=tasks.evaluationStudiesForOwner();if(selected.isEmpty()&&studies.length()>0)selected=studies.getJSONObject(0).getString("id");
        JSONObject result=new JSONObject().put("studies",studies).put("datasetId",EvaluationDataset.ID).put("datasetVersion",EvaluationDataset.VERSION).put("datasetSha256",EvaluationDataset.digest()).put("maximumTrials",6).put("maximumModelRequestsPerTrial",EvaluationDataset.MODEL_LIMIT).put("syntheticOnly",true);
        if(selected.isEmpty())return result;
        JSONObject study=tasks.evaluationStudyForOwner(selected);JSONArray all=tasks.evaluationTasksForOwner(selected),rows=new JSONArray();
        for(int i=0;i<all.length();i++){JSONObject task=all.getJSONObject(i);rows.put(new JSONObject().put("taskId",task.getString("id")).put("runId",task.optString("runId")).put("epoch",task.optInt("coordinatorEpoch")).put("status",task.getString("status")).put("evaluation",task.getJSONObject("evaluation")).put("running",active.containsKey(task.getString("id"))));}
        return result.put("study",study).put("tasks",rows).put("tuning",study.has("tuningSnapshot")?study.getJSONObject("tuningSnapshot"):EvaluationEvidence.tuning(all));
    }
    public JSONObject compare(String before,String after)throws Exception{
        JSONObject a=tasks.evaluationStudyForOwner(before),b=tasks.evaluationStudyForOwner(after);if(before.equals(after)||!a.getString("datasetSha256").equals(b.getString("datasetSha256")))throw new SecurityException("只对照不同实验的同版本数据集");
        JSONArray left=tasks.evaluationTasksForOwner(before),right=tasks.evaluationTasksForOwner(after),changes=new JSONArray();
        for(int i=0;i<left.length();i++){JSONObject x=left.getJSONObject(i).getJSONObject("evaluation"),xt=x.getJSONObject("trial"),xp=x.optJSONObject("probe");if(xp==null)continue;
            for(int j=0;j<right.length();j++){JSONObject y=right.getJSONObject(j).getJSONObject("evaluation"),yt=y.getJSONObject("trial"),yp=y.optJSONObject("probe");
                if(yp!=null&&xt.getString("caseId").equals(yt.getString("caseId"))&&xt.getString("strategy").equals(yt.getString("strategy")))changes.put(new JSONObject().put("caseId",xt.getString("caseId")).put("pool",xt.getString("pool")).put("strategy",xt.getString("strategy")).put("before",xp.getString("classification")).put("after",yp.getString("classification")).put("regression",xp.optBoolean("passed")&&!yp.optBoolean("passed")));}}
        return new JSONObject().put("beforeStudyId",before).put("afterStudyId",after).put("datasetSha256",a.getString("datasetSha256")).put("matchedCases",changes.length()).put("changes",changes).put("tuningFeedback",false).put("selectionChanged",false);
    }
}
