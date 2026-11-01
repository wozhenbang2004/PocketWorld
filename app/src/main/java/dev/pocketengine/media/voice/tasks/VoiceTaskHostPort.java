package dev.pocketengine.media.voice.tasks;

import dev.pocketengine.media.RealtimeReviewedTurn;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native-only adapter to the existing TaskStore/admission/Runner. Never a model or feature tool. */
public interface VoiceTaskHostPort {
    interface Authority {void check()throws Exception;}
    interface Listener {void changed(TaskState state);}
    interface Subscription extends AutoCloseable {@Override void close();}
    /** Read and verify this exact owner-reviewed source; absence never permits another creation. */
    TaskState inspectOriginal(Reference reference,Authority authority)throws Exception;
    /** Start the already persisted original task through the existing native admission path. */
    TaskState startOriginal(Command command,Authority authority)throws Exception;
    /** Cancel the exact expected run/epoch, atomically fenced against the host's live control. */
    TaskState cancelExactRun(Command command,Authority authority)throws Exception;
    /** Explicit native recovery reads an existing record; it never creates or resumes a task. */
    default Recovered recoverOriginal(String taskId,Authority authority)throws Exception{throw new UnsupportedOperationException("原语音任务恢复尚未安装");}
    default JSONArray recentOriginals(Authority authority)throws Exception{throw new UnsupportedOperationException("原语音任务列表尚未安装");}
    /** Original host events only. Closing this subscription never stops or cancels the task. */
    default Subscription observeOriginal(Reference reference,Authority authority,Listener listener)throws Exception{throw new UnsupportedOperationException("原任务事件观察尚未安装");}

    final class Reference {
        public final String taskId,sessionId,turnId,text,contentHash;
        public final long finalRevision;
        private final JSONObject source;
        Reference(RealtimeReviewedTurn reviewed)throws Exception{
            taskId=reviewed.commitId;sessionId=reviewed.sessionId;turnId=reviewed.turnId;text=reviewed.text;contentHash=reviewed.contentHash;finalRevision=reviewed.finalRevision;source=reviewed.source();
        }
        Reference(JSONObject original)throws Exception{
            taskId=original.getString("id");text=original.getString("goal");source=new JSONObject(original.getJSONObject("origin").toString());
            sessionId=source.getString("sessionId");turnId=source.getString("turnId");contentHash=source.getString("contentHash");finalRevision=source.getLong("finalRevision");
        }
        public JSONObject source()throws Exception{return new JSONObject(source.toString());}
        @Override public String toString(){return "VoiceTaskReference["+taskId+"]";}
    }
    final class Recovered {
        public final Reference reference;
        public final TaskState state;
        Recovered(Reference reference,TaskState state){this.reference=reference;this.state=state;}
    }
    final class Command {
        public final String requestId=UUID.randomUUID().toString();
        public final Reference reference;
        public final String expectedRunId;
        public final int expectedEpoch;
        public final long expectedRevision;
        Command(Reference reference,TaskState state){this.reference=reference;expectedRunId=state.runId;expectedEpoch=state.epoch;expectedRevision=state.revision;}
    }
    /** A bounded original-record projection, never raw tool arguments/checkpoints or a second task DB. */
    final class TaskState {
        public final String taskId,runId,status,summary;
        public final int epoch;
        public final long revision;
        public final boolean requiresNativeApproval;
        public TaskState(String taskId,String runId,int epoch,long revision,String status,String summary){
            this(taskId,runId,epoch,revision,status,summary,false);
        }
        public TaskState(String taskId,String runId,int epoch,long revision,String status,String summary,boolean requiresNativeApproval){
            if(taskId==null||!taskId.matches("[a-f0-9-]{36}")||runId==null||epoch<0||revision<1||!(runId.isEmpty()&&epoch==0||runId.matches("[a-f0-9-]{36}")&&epoch>0))throw new IllegalArgumentException("原任务或执行轮次无效");
            if(status==null||!status.matches("preparing|queued|running|paused|stopping|awaiting_user|needs_confirmation|completed|failed|cancelled|interrupted|reconciling"))throw new IllegalArgumentException("原任务状态无效");
            if(summary==null||summary.length()>2400)throw new IllegalArgumentException("原任务摘要过长");
            this.taskId=taskId;this.runId=runId;this.epoch=epoch;this.revision=revision;this.status=status;this.summary=summary;this.requiresNativeApproval=requiresNativeApproval;
        }
        public JSONObject toJson()throws Exception{return new JSONObject().put("taskId",taskId).put("runId",runId).put("epoch",epoch).put("revision",revision).put("status",status).put("summary",summary).put("requiresNativeApproval",requiresNativeApproval);}
        String fingerprint(){return runId+"/"+epoch+"/"+revision+"/"+status+"/"+summary+"/"+requiresNativeApproval;}
        boolean terminal(){return status.matches("completed|failed|cancelled|interrupted");}
    }
}
