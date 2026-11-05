package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Native owner terminal over the one original approved PTY. No new runner, shell,
 * execution scope or log mirror. Closing a view never cancels that original run. */
public final class ProjectOwnerTerminal {
    private final TaskStore tasks;private final LocalModule local;private final TaskStoreProjectHost.CurrentControl controls;
    public ProjectOwnerTerminal(TaskStore tasks,LocalModule local,TaskStoreProjectHost.CurrentControl controls){this.tasks=tasks;this.local=local;this.controls=controls;}
    public Session open(String task,String execution,RunControl observation)throws Exception{return new Session(tasks.ownerProjectTerminalForOwner(task,execution),observation);}
    public Session openObservation(String task,String execution,RunControl observation)throws Exception{return new Session(tasks.ownerProjectExecutionForOwner(task,execution),observation);}
    public final class Session implements AutoCloseable {
        private final TaskStore.ProjectTerminalTarget target;private final RunControl observation;private final AtomicBoolean closed=new AtomicBoolean();private boolean inputUncertain;private JSONObject lastInput;private ProjectTerminalScreen screen;
        private Session(TaskStore.ProjectTerminalTarget target,RunControl observation)throws Exception{this.target=target;this.observation=java.util.Objects.requireNonNull(observation);JSONObject meta=check().metadata();inputUncertain=meta.optInt("pendingInputCount")>0;if(meta.has("pendingInputOperationId"))lastInput=new JSONObject().put("taskId",target.taskId).put("runId",target.runId).put("coordinatorEpoch",target.epoch).put("operationId",meta.getString("pendingInputOperationId"));}
        private TaskStore.ProjectTerminalTarget check()throws Exception{if(closed.get())throw new IllegalStateException("终端视图已关闭");observation.check();TaskStore.ProjectTerminalTarget now=target.observingOnly()?tasks.ownerProjectExecutionForOwner(target.taskId,target.executionId):tasks.ownerProjectTerminalForOwner(target.taskId,target.executionId);if(!target.sameOriginal(now))throw new SecurityException("原终端执行代次已变化，未切换到其他会话");return now;}
        public synchronized JSONObject metadata()throws Exception{return check().metadata().put("inputUncertain",inputUncertain).put("viewerOnlyClose",true).put("secretInputSupported",false);}
        public synchronized JSONObject read(long cursor)throws Exception{
            check();JSONObject original=target.originalRequest(),plan=original.getJSONObject("plan"),exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,original.getString("stepId"))),binding=original.getJSONObject("binding");
            JSONObject backend=local.projectReceiptForHost(exact,binding,false),durable=ProjectSources.copy(backend);durable.remove("activity");tasks.recordProjectOperation(binding,exact,durable);tasks.finishOwnerLocalTerminalFromReceipt(binding);
            JSONObject policy=local.automaticProjectLogPolicyForHost(exact.optString("runtimeId","termux"));int pageBytes=policy.getInt("automaticLogPageBytes");if(pageBytes!=8192&&pageBytes!=32768)throw new IllegalStateException("runtime_native_log_policy_invalid");
            JSONObject log=local.projectLogForHost(exact,binding,cursor,pageBytes);check();if(backend.optJSONObject("activity")!=null)log.put("activity",ProjectSources.copy(backend.getJSONObject("activity")));return log.put("logObservationPolicy",policy).put("terminal",backend.optBoolean("receiptReceived")&&backend.optString("status").matches("completed|failed")).put("nativeStatus",backend.optString("status")).put("processExited",backend.optBoolean("processExited")).put("resourcesReleased",backend.optBoolean("resourcesReleased")).put("inputUncertain",inputUncertain);
        }
        /** Native page/session owns the cursor; navigation can close this display without stopping the process. */
        public synchronized JSONObject readScreen()throws Exception {
            JSONObject metadata=check().metadata();if(screen==null)screen=new ProjectTerminalScreen(target.executionId,metadata.getInt("columns"),metadata.getInt("rows"));else screen.resize(metadata.getInt("columns"),metadata.getInt("rows"));
            JSONObject page=read(screen.cursor()),view=screen.append(page);for(String key:new String[]{"terminal","nativeStatus","processExited","resourcesReleased","inputUncertain","activity","logObservationPolicy"})if(page.has(key))view.put(key,page.get(key));return view;
        }
        /** Native owner accepts the existing finite input scope. Each action is still
         * prepared/consumed/ACKed in the original journal; unknown is never resent. */
        public synchronized JSONObject input(JSONObject ownerAction)throws Exception{
            if(target.observingOnly())throw new SecurityException("只读观察不能升级为原PTY输入批准");
            check();if(inputUncertain)throw new IllegalStateException("原终端输入结果待核；没有自动重发或继续输入");JSONObject args=ProjectSources.copy(ownerAction).put("executionId",target.executionId);ProjectCommandSpec.inputRequest(args);if(screen!=null&&"resize".equals(args.getString("action")))ProjectTerminalScreen.requireProjectDimensions(args.getInt("columns"),args.getInt("rows"));
            RunControl original=controls.get(target.taskId);if(original==null||!original.matchesHostRun(target.taskId,target.runId,target.epoch)||original.isPaused())throw new SecurityException("原终端控制器已结束或暂停，不能恢复输入");original.check();tasks.projectProcessInputAllowed(target.taskId,target.runId,target.epoch,args);
            RunJournal journal=tasks.journal(target.taskId,target.runId,target.epoch,original);String op=journal.beginOperation("project_process_input",args);JSONObject binding=new JSONObject().put("taskId",target.taskId).put("runId",target.runId).put("coordinatorEpoch",target.epoch).put("operationId",op);lastInput=ProjectSources.copy(binding);boolean entered=false;
            try{observation.check();check();entered=true;JSONObject actual=local.projectProcessInputForHost(binding,target.executionId);journal.finishOperation(op,actual);inputUncertain=!actual.optBoolean("nativeInputSettled");if(screen!=null&&!inputUncertain&&actual.optBoolean("accepted")&&"resize".equals(args.getString("action")))screen.resize(args.getInt("columns"),args.getInt("rows"));return actual.put("operationId",op);}
            catch(Exception failed){if(!entered){journal.finishOperation(op,new JSONObject().put("status","not_dispatched").put("accepted",false).put("code","owner_terminal_not_dispatched").put("message","原终端动作未进入输入宿主；未发送"));}else{inputUncertain=true;try{journal.finishOperation(op,new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL).put("code","owner_terminal_input_unknown").put("message","原输入进入宿主后回执待核，只查询此原操作，不重发"));}catch(Exception record){failed.addSuppressed(record);}}throw failed;}
        }
        public synchronized JSONObject originalInput()throws Exception{check();if(lastInput==null)return new JSONObject().put("knownInput",false);JSONObject actual=tasks.projectProcessInputReceipt(lastInput);if(actual.optBoolean("nativeInputSettled")&&check().metadata().optInt("pendingInputCount")==0)inputUncertain=false;return actual.put("operationId",lastInput.getString("operationId")).put("knownInput",true);}
        /** Explicit original stop intent, not a view's dispose and not Control.cancel(). */
        public synchronized JSONObject stop()throws Exception{
            if(target.observingOnly())throw new SecurityException("只读观察不能停止执行；请从原任务的明确停止入口处理");
            check();RunControl original=controls.get(target.taskId);if(original==null||!original.matchesHostRun(target.taskId,target.runId,target.epoch))throw new SecurityException("原终端控制器不可用；请在任务核对原退出");JSONObject args=new JSONObject().put("executionId",target.executionId);RunJournal journal=tasks.journal(target.taskId,target.runId,target.epoch,original);String op=journal.beginOperation("project_stop",args);JSONObject binding=new JSONObject().put("taskId",target.taskId).put("runId",target.runId).put("coordinatorEpoch",target.epoch).put("operationId",op);JSONObject actual=local.handle("project.stopService",new JSONObject().put("executionId",target.executionId).put("binding",binding));journal.finishOperation(op,actual);return actual;
        }
        @Override public void close(){if(closed.compareAndSet(false,true))observation.cancel();}
    }
}
