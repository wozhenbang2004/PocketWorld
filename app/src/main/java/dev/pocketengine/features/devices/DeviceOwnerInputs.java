package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Ordinary paired-task input on the original TaskStore/Runner. Native session pointers
 * cannot be reconstructed from peer JSON. No private USER source, new grant or worker. */
public final class DeviceOwnerInputs {
    public static final String FORMAT="pocketworld.device-owner-input.v1";
    public interface Originals {
        /** Must be a lock-free native lookup; called while the original Tasks monitor is held. */
        DeviceGoalSession current(String taskId);
    }
    private final TaskStore tasks;private final Originals originals;
    private final ConcurrentHashMap<String,Wait> waiting=new ConcurrentHashMap<>();
    public DeviceOwnerInputs(TaskStore original,Originals originals){tasks=java.util.Objects.requireNonNull(original);this.originals=java.util.Objects.requireNonNull(originals);}
    private static final class Wait {
        final DeviceGoalSession.Finished finished;final String request;final long revision,pause;
        Wait(DeviceGoalSession.Finished finished,JSONObject identity){this.finished=finished;request=identity.optString("requestId");revision=identity.optLong("waitRevision");pause=finished.originalSession().originalControl().pauseRevision();}
    }
    /** Original Service after runnerFinished and native pause. This call never holds Tasks. */
    public void waiting(DeviceGoalSession.Finished finished)throws Exception{
        if(Thread.holdsLock(tasks))throw new SecurityException("不能持Tasks锁取得Session完成证明");finished.requireCurrent();DeviceGoalSession session=finished.originalSession();
        requireOriginal(session);JSONObject result=finished.result(),identity=question(result.optJSONObject("evidence"),session.taskId(),session.originalControl().hostRunIdentity().runId(),session.originalControl().hostRunIdentity().epoch());
        if(!"awaiting_user".equals(result.optString("status"))||identity==null||!session.originalControl().isPaused()){waiting.remove(session.taskId());return;}
        if(waiting.size()>=64&&!waiting.containsKey(session.taskId()))throw new IllegalStateException("原设备等待引用已满，未创建新队列");waiting.put(session.taskId(),new Wait(finished,identity));
    }
    public void released(String taskId,RunControl original){Wait prior=waiting.get(taskId);if(prior!=null&&prior.finished.originalSession().originalControl()==original)waiting.remove(taskId,prior);}
    public static final class Scope {
        private final DeviceOwnerInputs issuer;private final DeviceGoalSession session;private final Wait waiting;
        private final String task,run,allocation;private final int epoch;private final long pause;
        private Scope(DeviceOwnerInputs issuer,DeviceGoalSession session,Wait waiting)throws Exception{
            this.issuer=issuer;this.session=session;this.waiting=waiting;task=session.taskId();RunControl.HostRunIdentity identity=session.originalControl().hostRunIdentity();run=identity.runId();epoch=identity.epoch();allocation=session.admission().request().budget().getString("allocationId");pause=session.originalControl().pauseRevision();
        }
        public String taskId(){return task;}public String runId(){return run;}public int epoch(){return epoch;}public String allocationId(){return allocation;}public long pauseRevision(){return pause;}
        public RunControl originalControl(){return session.originalControl();}public DeviceGoalSession originalSession(){return session;}
        /** Primitive native identity only; no Session monitor/receiver gate is acquired under SQL. */
        public void requireNativeIdentity()throws Exception{
            if(issuer.originals.current(task)!=session||originalControl().isCancelled()||!originalControl().matchesHostRun(task,run,epoch)||session.ownerResultClosed())throw new SecurityException("原设备输入会话已改变或停止");
        }
        public void requireWaitingOutsideTasks()throws Exception{
            if(Thread.holdsLock(issuer.tasks))throw new SecurityException("不能持Tasks锁取得原Runner结束证明");requireNativeIdentity();
            if(waiting==null||issuer.waiting.get(task)!=waiting||waiting.finished.originalSession()!=session||!originalControl().isPaused()||originalControl().pauseRevision()!=waiting.pause||pause!=waiting.pause)throw new SecurityException("原普通问题等待或暂停版本已变化");waiting.finished.requireCurrent();
        }
        public void requireContinuationOutsideTasks()throws Exception{
            requireWaitingOutsideTasks();JSONObject evidence=waiting.finished.result().optJSONObject("evidence");
            for(String flag:new String[]{"extensionEndUnavailable","extensionCleanupPending","projectServiceDrainPending","projectServiceDrainEvidenceUnavailable"})if(evidence!=null&&evidence.has(flag)&&!Boolean.FALSE.equals(evidence.opt(flag)))throw new SecurityException("原设备清理未核对；回答仅保留");
        }
        public String questionRequestId(){return waiting==null?"":waiting.request;}public long waitRevision(){return waiting==null?0:waiting.revision;}
    }
    private void requireOriginal(DeviceGoalSession session)throws Exception{
        if(session==null||originals.current(session.taskId())!=session||session.originalControl().isCancelled()||!session.originalControl().hasHostRun()||session.ownerResultClosed())throw new SecurityException("没有原设备目标的活动native session");session.checkCurrent();
    }
    public Scope scope(String taskId,boolean answer)throws Exception{
        if(Thread.holdsLock(tasks))throw new SecurityException("先在Tasks锁外取得原Session");DeviceGoalSession session=originals.current(taskId);requireOriginal(session);Scope scope=new Scope(this,session,answer?waiting.get(taskId):null);
        if(answer)scope.requireWaitingOutsideTasks();else if(session.originalControl().isPaused())throw new SecurityException("运行中补充不代替问题回答、审批或接管");return scope;
    }
    public AgentRunner.OwnerInputs runnerInputs(DeviceGoalSession original)throws Exception{
        requireOriginal(original);Scope scope=new Scope(this,original,null);tasks.openDeviceOwnerInputMailbox(scope);
        return new AgentRunner.OwnerInputs(){@Override public JSONArray poll(JSONArray included,boolean boundary,boolean close,RunControl live)throws Exception{
            if(live!=scope.originalControl())throw new SecurityException("不能替换原设备输入控制器");return tasks.pollDeviceOwnerInputs(scope,included,boundary,close);
        }};
    }
    /** Display/correlation only. All writes still require fresh current peer/grant/control lease. */
    public JSONObject view(JSONObject task)throws Exception{
        if(!TaskStoreDeviceTasks.deviceTask(task))return null;DeviceGoalSession session=originals.current(task.getString("id"));
        JSONObject view=new JSONObject().put("format",FORMAT).put("nativePortPresent",true).put("taskId",task.getString("id")).put("runId",task.optString("runId")).put("executorEpoch",task.optInt("coordinatorEpoch"))
            .put("allocationId",task.getJSONObject("deviceExecutionScope").getString("allocationId")).put("sourceKind","native_paired_task_input").put("formalSourceRefAvailable",false).put("executionAuthorized",false);
        boolean live=session!=null&&!session.originalControl().isCancelled()&&!session.ownerResultClosed()&&session.originalControl().matchesHostRun(task.getString("id"),task.optString("runId"),task.optInt("coordinatorEpoch"));
        view.put("originalSessionPresent",live).put("runningInputState",live&&!session.originalControl().isPaused()&&task.optString("status").matches("preparing|running")?"requires_current_write_grant_and_control_lease":"unavailable");
        JSONObject question=question(task.optJSONObject("evidence"),task.getString("id"),task.optString("runId"),task.optInt("coordinatorEpoch"));if(question!=null&&"awaiting_user".equals(task.optString("status")))view.put("ordinaryQuestion",question);
        JSONArray rows=task.optJSONArray("ownerInputs");for(int i=rows==null?-1:rows.length()-1;i>=0;i--){JSONObject row=rows.getJSONObject(i),proof=row.optJSONObject("nativeDeviceInput");if(proof!=null&&FORMAT.equals(proof.optString("format"))&&task.optString("runId").equals(row.optString("acceptedRunId"))&&task.optInt("coordinatorEpoch")==row.optInt("acceptedEpoch")){
            view.put("lastInput",new JSONObject().put("messageId",row.getString("messageId")).put("kind",proof.getString("kind")).put("phase",proof.getString("phase")).put("sourceKind","native_paired_task_input").put("formalSourceRefAvailable",false).put("executionAuthorized",false));break;}}
        return view;
    }
    public static JSONObject question(JSONObject evidence,String task,String run,int epoch)throws Exception{
        JSONObject q=evidence==null?null:evidence.optJSONObject("ownerQuestion");if(q==null||!"pocketworld.owner-question.v1".equals(q.optString("format"))||!"answer".equals(q.optString("replyKind"))||!Boolean.FALSE.equals(q.opt("approval"))||!Boolean.FALSE.equals(q.opt("takeover"))||q.has("replyConsumed")&&!Boolean.FALSE.equals(q.opt("replyConsumed"))
                ||!task.equals(q.optString("taskId"))||!run.equals(q.optString("runId"))||epoch<1||epoch!=q.optInt("coordinatorEpoch"))return null;
        Object revision=q.opt("waitRevision");String request=q.optString("requestId");try{if(!UUID.fromString(request).toString().equalsIgnoreCase(request))return null;}catch(Exception bad){return null;}
        if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<1||!(q.opt("question") instanceof String)||q.getString("question").isBlank()||q.getString("question").length()>2000)return null;
        return new JSONObject().put("taskId",task).put("runId",run).put("executorEpoch",epoch).put("requestId",request).put("waitRevision",revision).put("question",q.getString("question")).put("approval",false).put("takeover",false);
    }
    public static JSONObject prepare(Scope original,JSONObject task,PairingCoordinator.Permission permission)throws Exception{
        original.requireNativeIdentity();DeviceTaskEnvelope envelope=permission.envelope();JSONObject args=envelope.arguments();boolean answer="task.answerQuestion".equals(envelope.method());
        if(!answer&&!"task.steer".equals(envelope.method())||!TaskStoreDeviceTasks.deviceTask(task)||!"GOAL".equals(task.optString("deviceExecutionKind"))||!original.taskId().equals(task.getString("id"))||!original.runId().equals(task.optString("runId"))||original.epoch()!=task.optInt("coordinatorEpoch")
                ||!original.allocationId().equals(args.getString("allocationId"))||!original.allocationId().equals(task.getJSONObject("deviceExecutionScope").getString("allocationId"))||!original.runId().equals(args.getString("runId"))||original.epoch()!=args.getInt("executorEpoch")||task.optBoolean("cancelRequested"))throw new SecurityException("输入不属于原peer任务/run/epoch/allocation");
        String text=args.getString("text");if(text.isBlank()||text.length()>4000||!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(text))throw new IllegalArgumentException("设备任务补充应为1至4000字");String digest=TaskStore.capabilityFingerprint(new JSONObject().put("text",text));JSONArray rows=task.optJSONArray("ownerInputs");
        if(answer){
            for(int i=0;rows!=null&&i<rows.length();i++){JSONObject prior=rows.getJSONObject(i),proof=prior.optJSONObject("nativeDeviceInput");if(proof!=null&&FORMAT.equals(proof.optString("format"))&&"answer".equals(proof.optString("kind"))&&args.getString("originalRequestId").equals(proof.optString("requestId"))&&original.runId().equals(prior.optString("acceptedRunId"))&&original.epoch()==prior.optInt("acceptedEpoch")){
                if(args.getLong("waitRevision")!=proof.optLong("waitRevision")||!digest.equals(proof.optString("textDigest"))||!permission.callerDeviceId().equals(proof.optString("callerDeviceId")))throw new SecurityException("同一原设备问题不能改写回答");return new JSONObject().put("row",new JSONObject(prior.toString())).put("duplicateInput",true);
            }}
            JSONObject q=question(task.optJSONObject("evidence"),original.taskId(),original.runId(),original.epoch());
            if(q==null||!"awaiting_user".equals(task.optString("status"))||!args.getString("originalRequestId").equals(q.getString("requestId"))||args.getLong("waitRevision")!=q.getLong("waitRevision")||!original.questionRequestId().equals(q.getString("requestId"))||original.waitRevision()!=q.getLong("waitRevision")||!original.originalControl().isPaused()||original.originalControl().pauseRevision()!=original.pauseRevision())throw new SecurityException("旧问题、审批或接管不能作为普通设备回答");
        }else if(!task.optString("status").matches("preparing|running")||original.originalControl().isPaused())throw new SecurityException("只有原活动设备目标接收运行中补充");
        long chars=text.length();for(int i=0;rows!=null&&i<rows.length();i++)chars+=rows.getJSONObject(i).optString("content").length();if(rows!=null&&rows.length()>=64||chars>240000)throw new IllegalStateException("原设备任务输入留存已满");
        JSONObject proof=new JSONObject().put("format",FORMAT).put("kind",answer?"answer":"steer").put("callerDeviceId",permission.callerDeviceId()).put("grantId",permission.grantId()).put("authorizationEpoch",permission.authorizationEpoch()).put("allocationId",original.allocationId()).put("operationId",envelope.operationId()).put("argumentsDigest",envelope.argumentsDigest()).put("textDigest",digest).put("phase","saved");
        if(answer)proof.put("requestId",args.getString("originalRequestId")).put("waitRevision",args.getLong("waitRevision"));
        JSONObject row=new JSONObject().put("messageId",UUID.randomUUID().toString()).put("clientMessageId",UUID.randomUUID().toString()).put("content",text).put("mode",answer?"queue":"steer").put("attachments",new JSONArray()).put("acceptedTaskId",original.taskId()).put("acceptedRunId",original.runId()).put("acceptedEpoch",original.epoch()).put("acceptedAt",System.currentTimeMillis()).put("sourceKind","native_paired_task_input").put("formalSourceRefAvailable",false).put("nativeDeviceInput",proof);
        return new JSONObject().put("row",row).put("duplicateInput",false);
    }
}
