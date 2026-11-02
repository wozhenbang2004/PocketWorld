package dev.pocketengine.features.conversation;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** A projection of host TaskStore snapshots. This class cannot create, resume or settle tasks. */
public final class TaskPresentation {
    public static final class Operation {
        public final String id,tool,state,label,output,runId;
        public final boolean uncertain,awaitingApproval;
        public final long at;
        Operation(String id,String tool,String state,String label,String output,String runId,boolean uncertain,boolean awaitingApproval,long at){this.id=id;this.tool=tool;this.state=state;this.label=label;this.output=output;this.runId=runId;this.uncertain=uncertain;this.awaitingApproval=awaitingApproval;this.at=at;}
    }
    public static final class Snapshot {
        public final String id,runId,goal,status,label,summary,next;
        public final boolean detailsLoaded,confirmedComplete;
        public final List<Operation> operations;
        public final List<InterruptedAttempts.Attempt> interruptedAttempts;
        public final int totalOperations;
        Snapshot(String id,String runId,String goal,String status,String label,String summary,String next,boolean loaded,List<Operation> operations,int total,List<InterruptedAttempts.Attempt> attempts){this.id=id;this.runId=runId;this.goal=goal;this.status=status;this.label=label;this.summary=summary;this.next=next;this.detailsLoaded=loaded;this.operations=List.copyOf(operations);this.totalOperations=total;this.confirmedComplete=status.equals("completed");this.interruptedAttempts=List.copyOf(attempts);}
    }
    private TaskPresentation(){}
    public static Snapshot fromTaskStore(JSONObject task){
        if(task==null)throw new IllegalArgumentException("Host task snapshot required");String status=task.optString("status");JSONArray all=task.optJSONArray("operations");List<Operation> operations=new ArrayList<>();int total=all==null?0:all.length();
        for(int i=Math.max(0,total-400);i<total;i++){
            JSONObject operation=all.optJSONObject(i);if(operation==null)continue;JSONObject receipt=operation.optJSONObject("receipt"),data=receipt==null?null:receipt.optJSONObject("data");String state=operation.optString("state"),tool=operation.optString("tool","工具操作");
            boolean uncertain=state.equals("unknown")||receipt!=null&&(receipt.has("accepted")&&receipt.isNull("accepted")||receipt.optString("status").equals("unknown")||receipt.optString("code").contains("unknown"));
            boolean awaitingApproval=receipt!=null&&receipt.optString("code").equals("approval_required");
            String label=uncertain?"结果待核对":state.equals("prepared")?"已登记，等待收据":awaitingApproval?"等待操作授权":receipt!=null&&receipt.has("ok")&&!receipt.optBoolean("ok")?"工具返回错误":receipt!=null&&receipt.optBoolean("accepted")?"调用已接受":state.equals("receipt")?"已有工具回执":"状态待确认";
            String output="";if(receipt!=null){for(String key:new String[]{"message","summary","text","stdout","stderr"}){String value=receipt.optString(key,"");if(!value.isBlank())output=append(output,key,value);}if(data!=null)for(String key:new String[]{"message","summary","text","stdout","stderr","name","path","mimeType"}){String value=data.optString(key,"");if(!value.isBlank())output=append(output,key,value);}}
            operations.add(new Operation(operation.optString("operationId"),tool,state,label,output,operation.optString("runId"),uncertain,awaitingApproval,operation.optLong("createdAt",operation.optLong("at"))));
        }
        String next=switch(status){case "reconciling","unknown"->"先核对可能已经发生的动作，原任务会等待";case "needs_confirmation"->"请到原任务核对结果，再确认是否完成";case "waiting_approval","awaiting_approval"->"需要在原任务确认这次具体操作";case "paused","cancelled","interrupted","failed","awaiting_user"->"查看已有结果，补充后可继续原任务";case "completed"->"原任务已结束；结果与过程仍保留";default->"过程与结果来自原任务，后续操作在原任务中处理";};
        JSONObject page=task.optJSONObject("historyEventPage");boolean partial=page==null||page.optBoolean("hasMore")||page.optBoolean("readLimited");
        return new Snapshot(task.optString("id"),task.optString("runId"),task.optString("goal"),status,statusLabel(status),task.optString("summary"),next,all!=null,operations,total,InterruptedAttempts.fromEvents(task.optJSONArray("events"),partial));
    }
    private static String append(String prior,String key,String value){String next=prior+(prior.isEmpty()?"":"\n\n")+(key.equals("stdout")?"标准输出\n":key.equals("stderr")?"错误输出\n":"")+value;if(next.length()<=16000)return next;int end=16000;if(Character.isHighSurrogate(next.charAt(end-1)))end--;return next.substring(0,end)+"\n（当前卡片只展示部分内容，请到原任务核对完整记录）";}
    public static String statusLabel(String status){return switch(status){case "queued"->"等待执行";case "preparing"->"准备中";case "running"->"执行中";case "pausing"->"正在暂停";case "paused"->"已暂停";case "stopping","cancelling"->"正在停止";case "verifying"->"正在核对";case "needs_confirmation"->"请确认结果";case "waiting_approval","awaiting_approval"->"等待操作授权";case "reconciling","unknown"->"等待核对动作";case "completed"->"已完成";case "cancelled","canceled","stopped"->"已停止";case "failed"->"未完成";case "interrupted"->"已中断，等待恢复";case "awaiting_user","waiting_user"->"等待你处理";default->status.isBlank()?"状态待确认":status;};}
}
