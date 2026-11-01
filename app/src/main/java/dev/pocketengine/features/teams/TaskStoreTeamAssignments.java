package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Thin native adapter to the original TaskStore. No task, permission, journal or runner implementation here. */
public final class TaskStoreTeamAssignments implements TeamAssignmentHost.Tasks {
    private final TaskStore tasks;private final Consumer<String> changed;
    public TaskStoreTeamAssignments(TaskStore tasks,Consumer<String> changed){this.tasks=java.util.Objects.requireNonNull(tasks);this.changed=java.util.Objects.requireNonNull(changed);}
    @Override public JSONObject begin(TeamCoordinator.AssignmentCall call,RunControl original,int allocation)throws Exception {JSONObject binding=tasks.startTeamAssignment(call,original,allocation);changed.accept(call.assignmentId);return binding;}
    @Override public AutoCloseable retainExecution(JSONObject binding,RunControl original)throws Exception {JSONObject retained=new JSONObject(binding.toString());return original.retainAuthorityCheck(()->tasks.checkTeamAssignmentExecutionCurrent(retained));}
    @Override public TeamWorkFiles.Host files(TeamCoordinator.AssignmentCall call,JSONObject binding,RunControl original)throws Exception{TeamWorkFiles.Host nativeHost=fileHost(binding,original);return new TeamWorkFiles.Host(){
        public void check(String tool,RunControl live)throws Exception{nativeHost.check(tool,live);}
        public JSONObject claim(String op,String tool,JSONObject args,JSONObject capability,RunControl live)throws Exception{return nativeHost.claim(op,tool,args,capability,live);}
        public JSONArray receipts(String tool)throws Exception{return nativeHost.receipts(tool);}
        public JSONObject question(String op,String text,RunControl live)throws Exception{return nativeHost.question(op,text,live);}
        public JSONObject message(String op,JSONObject args,RunControl live)throws Exception{nativeHost.check("team_send_message",live);return call.queuePeerMessage(op,args);}
    };}
    public TeamWorkFiles.Host fileHost(JSONObject binding,RunControl original){return new TeamWorkFiles.Host(){
        @Override public void check(String tool,RunControl control)throws Exception {if(control!=original)throw new SecurityException("不是原assignment控制器");tasks.checkTeamAssignment(binding,tool,control);}
        @Override public JSONObject claim(String operation,String tool,JSONObject args,JSONObject capability,RunControl control)throws Exception {check(tool,control);JSONObject receipt=tasks.claimTeamFileOperation(binding,operation,tool,args,capability,control);changed.accept(binding.getString("taskId"));return receipt;}
        @Override public JSONArray receipts(String tool)throws Exception {return tasks.teamAssignmentReceipts(binding,tool);}
        @Override public JSONObject question(String operation,String question,RunControl control)throws Exception {check("team_request_input",control);JSONObject receipt=tasks.askTeamAssignment(binding,operation,question,control);changed.accept(binding.getString("taskId"));return receipt;}
    };}
    @Override public RunJournal journal(JSONObject binding,RunControl original)throws Exception {return tasks.journal(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),original);}
    @Override public AgentRunner.OwnerInputs inputs(JSONObject binding,RunControl original){return tasks.teamAssignmentInputs(binding,original);}
    @Override public void reserveInference(JSONObject binding,String dedupKey)throws Exception {tasks.reserveTeamInference(binding,"member",dedupKey);}
    @Override public dev.pocketengine.core.CapabilityPort meter(dev.pocketengine.core.CapabilityPort original,JSONObject binding)throws Exception{return tasks.teamUsesWorkItems(binding.getString("teamRunId"))?new TeamBudgetPort(original,tasks,binding):original;}
    @Override public void event(JSONObject binding,JSONObject event)throws Exception {tasks.event(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),event);changed.accept(binding.getString("taskId"));}
    @Override public void end(JSONObject binding,RunOutcome outcome)throws Exception {
        String status=outcome.status;if(status.equals("needs_confirmation")){JSONArray frozen=tasks.teamAssignmentReceipts(binding,"team_freeze_outputs");status=frozen.length()>0?"completed":"awaiting_user";}
        tasks.endAttempt(binding.getString("taskId"),binding.getString("runId"),status,outcome.summary,outcome.evidence);changed.accept(binding.getString("taskId"));
    }
    @Override public JSONObject inspect(TeamCoordinator.AssignmentCall call)throws Exception{return tasks.inspectTeamAssignment(call);}
}
