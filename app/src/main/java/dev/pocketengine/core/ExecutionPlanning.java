package dev.pocketengine.core;

import java.util.Objects;
import java.util.Set;
import org.json.JSONObject;

/** A live view of the original task scope. JSON projections never recreate this authority. */
public abstract class ExecutionPlanning implements AutoCloseable {
    public static final String SUBMIT="execution_plan_submit",REQUEST="execution_phase_request",REVIEW="execution_plan_review";
    public enum Access { READ, RESTRICTED_PROPOSAL, DENY }
    public enum ReadOutcome { RECEIPT, FAILED, UNCONFIRMED }
    private final RunControl original;

    protected ExecutionPlanning(RunControl original){this.original=Objects.requireNonNull(original);}
    protected final RunControl original(){return original;}

    public static final class Snapshot {
        private final ExecutionPlanning issuer;
        public final String taskId,runId,phase,planArtifactRef,planHash;
        public final int epoch;
        public final long scopeRevision,planRevision,pauseRevision;
        private Snapshot(ExecutionPlanning issuer,RunControl.HostRunIdentity identity,long scopeRevision,String phase,long planRevision,String reference,String hash,long pauseRevision){
            this.issuer=issuer;taskId=identity.taskId();runId=identity.runId();epoch=identity.epoch();this.scopeRevision=scopeRevision;this.phase=phase;this.planRevision=planRevision;planArtifactRef=reference;planHash=hash;this.pauseRevision=pauseRevision;
        }
        public boolean planning(){return "planning".equals(phase);}
        public JSONObject view()throws Exception{return new JSONObject().put("taskId",taskId).put("runId",runId).put("epoch",epoch).put("scopeRevision",scopeRevision).put("executionPhase",phase).put("planRevision",planRevision).put("planArtifactRef",planArtifactRef).put("planHash",planHash).put("authorizesExecution",false);}
    }

    public static final class ReadClaim {
        private final ExecutionPlanning issuer;
        public final Snapshot snapshot;
        public final String operationId,tool,argumentsHash;
        private ReadClaim(ExecutionPlanning issuer,Snapshot snapshot,String operationId,String tool,String argumentsHash){this.issuer=issuer;this.snapshot=snapshot;this.operationId=operationId;this.tool=tool;this.argumentsHash=argumentsHash;}
    }

    protected final Snapshot snapshot(long revision,String phase,long planRevision,String reference,String hash){
        RunControl.HostRunIdentity identity=original.hostRunIdentity();
        if(identity==null||revision<1||!Set.of("planning","executing").contains(phase)||planRevision<0)throw new SecurityException("没有原任务的准确规划身份");
        return new Snapshot(this,identity,revision,phase,planRevision,Objects.requireNonNull(reference),Objects.requireNonNull(hash),original.pauseRevision());
    }
    protected final void requireOwned(Snapshot value){
        if(value==null||value.issuer!=this||!original.matchesHostRun(value.taskId,value.runId,value.epoch))throw new SecurityException("规划快照不属于原执行器");
    }
    protected final void requireOwned(ReadClaim value){if(value==null||value.issuer!=this)throw new SecurityException("读取凭证不属于原执行器");requireOwned(value.snapshot);}
    protected final ReadClaim readClaim(Snapshot value,String operation,String tool,String hash){requireOwned(value);if(operation==null||!operation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||hash==null||!hash.matches("[0-9a-f]{64}"))throw new SecurityException("缺少原读取记录");return new ReadClaim(this,value,operation,tool,hash);}

    /** Runner-owned observations and bounded stop/question receipts; never an effect or grant. */
    public static Access runnerAccess(String tool){
        if(SUBMIT.equals(tool)||REQUEST.equals(tool))return Access.RESTRICTED_PROPOSAL;
        return Set.of("phone_observe","phone_observation_help","phone_list_apps","phone_wait","phone_routes","phone_past_screens","phone_view_past_screen","phone_request_user","run_handle_obstacle","run_end_not_done","run_ask_user").contains(tool)?Access.READ:Access.DENY;
    }

    public abstract Snapshot capture()throws Exception;
    public abstract void requireCurrent(Snapshot expected)throws Exception;
    /** nativeAccess is supplied by the actual native route, never by tool arguments. */
    public abstract ReadClaim beginRead(Snapshot expected,String tool,JSONObject originalArguments,String runnerCallId,Access nativeAccess)throws Exception;
    public abstract void finishRead(ReadClaim original,JSONObject actualReceipt,ReadOutcome outcome)throws Exception;
    /** The actual consumer must find the same live native read claim, not just a JSON operation ID. */
    public abstract void requireReadDispatch(String tool,JSONObject arguments,RunControl original)throws Exception;
    public abstract JSONObject submitPlan(Snapshot expected,JSONObject proposal,String runnerCallId)throws Exception;
    public abstract JSONObject requestPhase(Snapshot expected,String requestedPhase,String reason,String runnerCallId)throws Exception;
    @Override public void close(){ }
}
