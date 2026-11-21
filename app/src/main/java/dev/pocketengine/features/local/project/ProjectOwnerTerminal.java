package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Native owner terminal over the one original approved PTY. No new runner, shell,
 * execution scope or log mirror. Closing a view never cancels that original run. */
public final class ProjectOwnerTerminal {
    private final TaskStore tasks;private final LocalModule local;private final TaskStoreProjectHost.CurrentControl controls;
    public ProjectOwnerTerminal(TaskStore tasks,LocalModule local,TaskStoreProjectHost.CurrentControl controls){this.tasks=tasks;this.local=local;this.controls=controls;}
    public Session open(String task,String execution,RunControl observation)throws Exception{return new Session(tasks.ownerProjectTerminalForOwner(task,execution),observation);}
    public Session openObservation(String task,String execution,RunControl observation)throws Exception{return new Session(tasks.ownerProjectExecutionForOwner(task,execution),observation);}
    /** The existing explicit owner observe action alone may look for this job's first durable result. */
    public Session openExplicitObservation(String task,String execution,RunControl observation,BooleanSupplier currentOwner)throws Exception{
        if(currentOwner==null||!currentOwner.getAsBoolean())throw new SecurityException("原执行核对窗口已关闭");
        var target=tasks.ownerProjectExecutionForOwner(task,execution,true);
        if(!target.firstReceiptLookup())throw new SecurityException("原执行回执已更新，请从同一任务重新读取；没有重新派发。");
        return new Session(target,observation,currentOwner);
    }
    public JSONObject readStoredTermuxCommand(String task,String execution,String source,String channel,String expected)throws Exception{
        return tasks.ownerTermuxCommandRecord(task,execution).read(source,channel,expected,(exact,binding)->local.projectReceiptForHost(exact,binding,false));
    }
    public final class Session implements AutoCloseable {
        private static final String FIRST_RECEIPT_UNKNOWN="尚未读到这次执行的已结束回执，结果仍待核对。没有重新派发。";
        private final TaskStore.ProjectTerminalTarget target;private final RunControl observation;private final AtomicBoolean closed=new AtomicBoolean();private boolean inputUncertain;private JSONObject lastInput;private ProjectTerminalScreen screen;
        private BooleanSupplier firstReceiptWindow;
        private Session(TaskStore.ProjectTerminalTarget target,RunControl observation)throws Exception{this(target,observation,null);}
        private Session(TaskStore.ProjectTerminalTarget target,RunControl observation,BooleanSupplier currentOwner)throws Exception{
            this.target=target;this.observation=java.util.Objects.requireNonNull(observation);
            if(target.firstReceiptLookup())firstReceiptWindow=java.util.Objects.requireNonNull(currentOwner);
            JSONObject meta=check().metadata();inputUncertain=meta.optInt("pendingInputCount")>0;if(meta.has("pendingInputOperationId"))lastInput=new JSONObject().put("taskId",target.taskId).put("runId",target.runId).put("coordinatorEpoch",target.epoch).put("operationId",meta.getString("pendingInputOperationId"));
        }
        private void checkFirstReceiptWindow(){
            if(firstReceiptWindow!=null&&!firstReceiptWindow.getAsBoolean())throw new SecurityException("原执行核对窗口已关闭");
        }
        private TaskStore.ProjectTerminalTarget check()throws Exception{
            if(closed.get())throw new IllegalStateException("终端视图已关闭");observation.check();checkFirstReceiptWindow();
            TaskStore.ProjectTerminalTarget now=target.observingOnly()?tasks.ownerProjectExecutionForOwner(target.taskId,target.executionId,firstReceiptWindow!=null):tasks.ownerProjectTerminalForOwner(target.taskId,target.executionId);
            if(!target.sameOriginal(now))throw new SecurityException("原终端执行代次已变化，未切换到其他会话");
            checkFirstReceiptWindow();return now;
        }
        private JSONObject viewerMetadata(TaskStore.ProjectTerminalTarget current)throws Exception{
            return current.metadata().put("inputUncertain",inputUncertain).put("viewerOnlyClose",true).put("secretInputSupported",false);
        }
        public synchronized JSONObject metadata()throws Exception{
            if(firstReceiptWindow!=null)throw new IllegalStateException(FIRST_RECEIPT_UNKNOWN);
            return viewerMetadata(check());
        }
        /** No observing viewer or first page until the same owner source and window have passed the full read. */
        public synchronized JSONObject firstObservation()throws Exception{
            if(firstReceiptWindow==null)throw new IllegalStateException(FIRST_RECEIPT_UNKNOWN);
            JSONObject output=readScreen(),metadata=viewerMetadata(check());
            JSONObject result=new JSONObject().put("metadata",metadata).put("output",output);
            checkFirstReceiptWindow();firstReceiptWindow=null;
            return result;
        }
        public synchronized JSONObject read(long cursor)throws Exception{
            TaskStore.ProjectTerminalTarget current=check();
            JSONObject original=target.originalRequest(),plan=original.getJSONObject("plan"),exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,original.getString("stepId"))),binding=original.getJSONObject("binding");
            boolean first=firstReceiptWindow!=null;
            JSONObject backend;
            if(first){
                if(!current.firstReceiptLookup())throw new SecurityException("原执行回执已更新，请从同一任务重新读取；没有重新派发。");
                checkFirstReceiptWindow();
                backend=local.firstManagedNodeReceiptForHost(current,firstReceiptWindow);
                if(backend==null||!OwnerDraftFirstBuild.verifiedFirstTerminalObservation(exact,binding,backend))
                    throw new IllegalStateException(FIRST_RECEIPT_UNKNOWN);
                tasks.recordFirstProjectReceiptForOwner(target,backend,firstReceiptWindow);
            }else{
                backend=local.projectReceiptForHost(exact,binding,false);
                JSONObject durable=ProjectSources.copy(backend);durable.remove("activity");
                tasks.recordProjectOperation(binding,exact,durable);tasks.finishOwnerLocalTerminalFromReceipt(binding);
            }
            JSONObject policy=local.automaticProjectLogPolicyForHost(exact.optString("runtimeId","termux"));int pageBytes=policy.getInt("automaticLogPageBytes");if(pageBytes!=8192&&pageBytes!=32768)throw new IllegalStateException("runtime_native_log_policy_invalid");
            JSONObject log;
            if(first){log=local.firstManagedNodeLogForHost(target,check(),cursor,pageBytes,firstReceiptWindow);}
            else log=local.projectLogForHost(exact,binding,cursor,pageBytes);
            check();
            JSONObject exit=ProjectExecutionExit.from(exact,binding,backend);if(exit!=null)log.put("observedExit",exit);
            if(backend.optJSONObject("activity")!=null)log.put("activity",ProjectSources.copy(backend.getJSONObject("activity")));
            return log.put("logObservationPolicy",policy).put("terminal",backend.optBoolean("receiptReceived")&&backend.optString("status").matches("completed|failed")).put("nativeStatus",backend.optString("status")).put("processExited",backend.optBoolean("processExited")).put("resourcesReleased",backend.optBoolean("resourcesReleased")).put("inputUncertain",inputUncertain);
        }
        /** Native page/session owns the cursor; navigation can close this display without stopping the process. */
        public synchronized JSONObject readScreen()throws Exception {
            JSONObject metadata=check().metadata();if(screen==null)screen=new ProjectTerminalScreen(target.executionId,metadata.getInt("columns"),metadata.getInt("rows"));else screen.resize(metadata.getInt("columns"),metadata.getInt("rows"));
            JSONObject page=read(screen.cursor()),view=screen.append(page);for(String key:new String[]{"terminal","nativeStatus","processExited","resourcesReleased","inputUncertain","activity","logObservationPolicy","observedExit"})if(page.has(key))view.put(key,page.get(key));return view;
        }
        /** Native owner accepts the existing finite input scope. Each action is still
         * prepared/consumed/ACKed in the original journal; unknown is never resent. */
        public synchronized JSONObject input(JSONObject ownerAction)throws Exception{
            if(target.observingOnly())throw new SecurityException("只读观察不能升级为原PTY输入批准");
            check();if(inputUncertain)throw new IllegalStateException("原终端输入结果待核；没有自动重发或继续输入");JSONObject args=ProjectSources.copy(ownerAction).put("executionId",target.executionId);ProjectCommandSpec.inputRequest(args);if(screen!=null&&"resize".equals(args.getString("action")))ProjectTerminalScreen.requireProjectDimensions(args.getInt("columns"),args.getInt("rows"));
            RunControl original=controls.get(target.taskId);if(original==null||!original.matchesHostRun(target.taskId,target.runId,target.epoch)||original.isPaused())throw new SecurityException("原终端控制器已结束或暂停，不能恢复输入");original.check();tasks.projectProcessInputAllowed(target.taskId,target.runId,target.epoch,args);
            RunJournal journal=tasks.journal(target.taskId,target.runId,target.epoch,original);String op=journal.beginOperation("project_process_input",args);JSONObject binding=new JSONObject().put("taskId",target.taskId).put("runId",target.runId).put("coordinatorEpoch",target.epoch).put("operationId",op);lastInput=ProjectSources.copy(binding);boolean entered=false;
            try{observation.check();check();entered=true;JSONObject actual=local.projectProcessInputForHost(binding,target.executionId);journal.finishOperation(op,actual);inputUncertain=!actual.optBoolean("nativeInputSettled");if(screen!=null&&!inputUncertain&&actual.optBoolean("accepted")&&"resize".equals(args.getString("action")))screen.resize(args.getInt("columns"),args.getInt("rows"));return actual.put("operationId",op);}
            catch(Exception failed){if(!entered){journal.finishOperation(op,new JSONObject().put("status","not_dispatched").put("accepted",false).put("nativeInputSettled",true).put("code","owner_terminal_not_dispatched").put("message","原终端动作未进入输入宿主；未发送"));}else{inputUncertain=true;try{journal.finishOperation(op,new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL).put("code","owner_terminal_input_unknown").put("message","原输入进入宿主后回执待核，只查询此原操作，不重发"));}catch(Exception record){failed.addSuppressed(record);}}throw failed;}
        }
        public synchronized JSONObject originalInput()throws Exception{check();if(lastInput==null)return new JSONObject().put("knownInput",false).put("inputUncertain",inputUncertain);JSONObject actual=tasks.projectProcessInputReceipt(lastInput);if(actual.optBoolean("nativeInputSettled")&&check().metadata().optInt("pendingInputCount")==0)inputUncertain=false;return actual.put("operationId",lastInput.getString("operationId")).put("knownInput",true).put("inputUncertain",inputUncertain);}
        /** Explicit original stop intent, not a view's dispose and not Control.cancel(). */
        public synchronized JSONObject stop()throws Exception{
            if(target.observingOnly())throw new SecurityException("只读观察不能停止执行；请从原任务的明确停止入口处理");
            check();RunControl original=controls.get(target.taskId);if(original==null||!original.matchesHostRun(target.taskId,target.runId,target.epoch))throw new SecurityException("原终端控制器不可用；请在任务核对原退出");JSONObject args=new JSONObject().put("executionId",target.executionId);RunJournal journal=tasks.journal(target.taskId,target.runId,target.epoch,original);String op=journal.beginOperation("project_stop",args);JSONObject binding=new JSONObject().put("taskId",target.taskId).put("runId",target.runId).put("coordinatorEpoch",target.epoch).put("operationId",op);JSONObject actual=local.handle("project.stopService",new JSONObject().put("executionId",target.executionId).put("binding",binding));journal.finishOperation(op,actual);return actual;
        }
        @Override public void close(){if(closed.compareAndSet(false,true))observation.cancel();}
    }
}

