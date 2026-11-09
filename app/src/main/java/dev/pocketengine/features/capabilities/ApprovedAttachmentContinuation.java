package dev.pocketengine.features.capabilities;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** One native continuation of an exact, definitely unissued local creation call. */
public final class ApprovedAttachmentContinuation implements AgentRunner.ApprovedCall {
    private final TaskStore tasks;
    private final String taskId,runId,approvalId;
    private final int epoch;
    private boolean taken;

    public ApprovedAttachmentContinuation(TaskStore tasks,String taskId,String runId,int epoch,String approvalId){
        this.tasks=java.util.Objects.requireNonNull(tasks);this.taskId=taskId;this.runId=runId;this.epoch=epoch;this.approvalId=approvalId;
    }

    @Override public synchronized JSONObject take(JSONArray history,RunControl original)throws Exception {
        if(taken)throw new SecurityException("原审批续接已取用，不能再次派发");
        original.check();
        if(!original.matchesHostRun(taskId,runId,epoch))throw new SecurityException("原审批控制器已改变");
        JSONObject task=tasks.approvalContextSnapshot(taskId),approval=null;
        if(task==null||!runId.equals(task.optString("runId"))||epoch!=task.optInt("coordinatorEpoch")||task.optBoolean("cancelRequested"))throw new SecurityException("原审批执行已改变");
        JSONArray approvals=task.optJSONArray("approvals");
        for(int i=0;approvals!=null&&i<approvals.length();i++){
            JSONObject row=approvals.getJSONObject(i);
            if(approvalId.equals(row.optString("id"))){if(approval!=null)throw new SecurityException("原审批编号重复");approval=row;}
        }
        if(approval==null||!runId.equals(approval.optString("runId"))||!"approved".equals(approval.optString("state"))||approval.optLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("原准确审批已失效");
        String tool=approval.getString("tool");
        CapabilityDescriptor descriptor;
        if("attachments_create_document".equals(tool))descriptor=CapabilityDescriptor.createDocument();
        else if("attachments_create_text".equals(tool))descriptor=CapabilityDescriptor.createText();
        else throw new SecurityException("该工具尚无准确调用续接入口");
        String callId=null;int receiptIndex=-1;
        for(int i=0;i<history.length();i++){
            JSONObject row=history.getJSONObject(i);if(!"tool".equals(row.optString("role")))continue;
            JSONObject receipt;try{receipt=new JSONObject(row.getString("content"));}catch(Exception notJson){continue;}
            if(!approvalId.equals(receipt.optString("approvalId")))continue;
            if(!"approval_required".equals(receipt.optString("code"))||!Boolean.FALSE.equals(receipt.opt("accepted")))throw new SecurityException("原审批存在不能证明未派发的回执");
            callId=row.getString("tool_call_id");receiptIndex=i;
        }
        JSONObject found=null;
        for(int i=0;i<receiptIndex;i++){
            JSONObject row=history.getJSONObject(i);if(!"assistant".equals(row.optString("role")))continue;
            JSONArray calls=row.optJSONArray("tool_calls");
            for(int j=0;calls!=null&&j<calls.length();j++){
                JSONObject call=calls.getJSONObject(j);if(!callId.equals(call.optString("id")))continue;
                if(found!=null)throw new SecurityException("原工具调用编号重复");found=call;
            }
        }
        if(found==null)throw new SecurityException("原审批调用正文不可恢复；没有用展示参数或模型新参数替代");
        JSONObject function=found.getJSONObject("function");
        if(!tool.equals(function.getString("name")))throw new SecurityException("原审批工具不匹配");
        JSONObject arguments=new JSONObject(function.getString("arguments"));
        if(!approval.getString("argumentsHash").equals(TaskStore.capabilityFingerprint(descriptor.normalize(arguments))))throw new SecurityException("原审批参数已变化或被脱敏，未派发");
        // The usual adapter still validates current resource identity, consumes the exact
        // approval and prepares a new operation. The old non-dispatch record stays intact.
        JSONObject call=new JSONObject().put("id","approval_"+java.util.UUID.randomUUID()).put("type","function")
                .put("function",new JSONObject().put("name",tool).put("arguments",arguments.toString()));
        taken=true;
        return new JSONObject().put("tool_calls",new JSONArray().put(call)).put("nativeApprovalId",approvalId);
    }
}
