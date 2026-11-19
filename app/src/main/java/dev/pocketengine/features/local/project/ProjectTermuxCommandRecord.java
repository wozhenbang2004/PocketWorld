package dev.pocketengine.features.local.project;

import dev.pocketengine.features.local.runtime.RuntimeExecutionRef;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONObject;

/** Only a previously approved external Termux command in a private original
 * project task. Both sources are local records, not a live Termux connection. */
public final class ProjectTermuxCommandRecord {
    private final JSONObject task,row,exact,binding,route;
    private final RuntimeExecutionRef reference;
    private ProjectTermuxCommandRecord(JSONObject task,JSONObject row,JSONObject exact,JSONObject binding,JSONObject route)throws Exception{
        this.task=task;this.row=row;this.exact=exact;this.binding=binding;this.route=route;reference=RuntimeExecutionRef.fromHost(exact,binding);
    }
    public static ProjectTermuxCommandRecord original(JSONObject task,JSONObject row,int recordedEpoch)throws Exception{
        // Use the full original plan resolved from the private journal, never one supplied by the interface.
        JSONObject original=ProjectDevelopment.originalPollRequest(task,row),plan=original.getJSONObject("plan"),binding=original.getJSONObject("binding");
        JSONObject exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,original.getString("stepId")));
        if(!"termux".equals(task.getJSONObject("developmentContext").opt("runtime"))||!"termux".equals(plan.optString("runtimeId","termux"))||
            !"termux.run".equals(exact.opt("command"))||binding.getInt("coordinatorEpoch")!=recordedEpoch)
            throw new SecurityException("这笔原工作不是外部 Termux 的命令执行，不打开终端或其他运行位置");
        return new ProjectTermuxCommandRecord(task,row,exact,binding,row.getJSONObject("receipt").getJSONObject("projectExecution"));
    }
    @FunctionalInterface public interface Reader{JSONObject saved(JSONObject exact,JSONObject binding)throws Exception;}
    public JSONObject read(String source,String channel,String expected,Reader privateFile)throws Exception{
        if(!Set.of("journal","callback").contains(source)||!Set.of("summary","stdout","stderr").contains(channel)||
            channel.equals("summary")&&!expected.isEmpty()||!channel.equals("summary")&&!expected.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("请先读取选中来源的原回执摘要");
        JSONObject job=source.equals("journal")?row.getJSONObject("receipt").optJSONObject("executionReceipt"):privateFile.saved(ProjectSources.copy(exact),ProjectSources.copy(binding));
        JSONObject shown=new JSONObject().put("format","pocketworld.owner-termux-command-record.v1").put("taskId",binding.getString("taskId")).put("runId",binding.getString("runId"))
            .put("epoch",binding.getInt("coordinatorEpoch")).put("executionId",reference.operationId()).put("projectId",reference.projectId()).put("checkoutId",reference.checkoutId())
            .put("stepId",exact.getString("stepId")).put("planDigest",exact.getString("planDigest")).put("sourceDigest",reference.sourceDigest()).put("readSource",source);
        if(job==null){if(!channel.equals("summary"))throw new IllegalStateException("原任务没有保存这个输出");return shown.put("callbackState","not_recorded").put("hasJob",false);}
        reference.requireReceipt(job);
        // Reuse the same original receipt and argv checks for either source without modifying the journal.
        JSONObject envelope=new JSONObject().put("projectExecution",route).put("executionReceipt",job);
        JSONObject checked=new JSONObject().put("tool",row.getString("tool")).put("runId",row.getString("runId")).put("operationId",row.getString("operationId")).put("arguments",row.getJSONObject("arguments")).put("receipt",envelope);
        JSONObject norm=ProjectDevelopment.recordedReceipt(task,checked);
        if(norm==null||!reference.operationId().equals(norm.opt("jobId")))throw new SecurityException("所选来源不能与原项目命令核对");
        String status=job.optString("status"),state;Object raw=job.opt("exitCode");Integer code=(raw instanceof Integer||raw instanceof Long)&&((Number)raw).longValue()>Integer.MIN_VALUE&&((Number)raw).longValue()<=Integer.MAX_VALUE?((Number)raw).intValue():null;
        boolean callback=Boolean.TRUE.equals(job.opt("receiptReceived"));
        if(!callback)state="not_recorded";else if(code!=null&&(code==0&&status.equals("completed")||code!=0&&status.equals("failed"))){state="exit_code";shown.put("exitCode",code);}
        else state="incomplete";
        shown.put("hasJob",true).put("callbackState",state).put("receiptIdentity",norm.getString("receiptIdentity")).put("sourceVerifiedBeforeExec",Boolean.TRUE.equals(norm.opt("sourceVerifiedBeforeExec")));
        if(job.opt("cancelRequested") instanceof Boolean)shown.put("cancelRequested",job.getBoolean("cancelRequested"));
        if(job.opt("outputTruncated") instanceof Boolean)shown.put("outputTruncated",job.getBoolean("outputTruncated"));
        String out=callback&&job.opt("stdout") instanceof String?job.getString("stdout"):null,err=callback&&job.opt("stderr") instanceof String?job.getString("stderr"):null;
        if(out!=null&&out.length()>65536||err!=null&&err.length()>65536)throw new IllegalStateException("超出原 Termux 每通道 65536 字符的留存范围，不拼接或返回");
        shown.put("stdoutChars",out==null?-1:out.length()).put("stderrChars",err==null?-1:err.length());
        JSONObject version=ProjectSources.copy(shown);if(out!=null)version.put("stdoutHash",ProjectSources.sha256(out.getBytes(StandardCharsets.UTF_8)));if(err!=null)version.put("stderrHash",ProjectSources.sha256(err.getBytes(StandardCharsets.UTF_8)));
        String marker=ProjectSources.digest(version);shown.put("recordDigest",marker);
        if(!channel.equals("summary")){
            if(!marker.equals(expected))throw new IllegalStateException("这份回执自查看摘要后有变化，请重新读取摘要，不拼接不同版本");
            String value=channel.equals("stdout")?out:err;if(value==null)throw new IllegalStateException("选定来源没有记录这个输出通道");
            int start=Math.max(0,value.length()-2000);if(start>0&&Character.isLowSurrogate(value.charAt(start))&&Character.isHighSurrogate(value.charAt(start-1)))start++;
            shown.put("channel",channel).put("text",value.substring(start)).put("start",start).put("total",value.length());
        }
        return shown;
    }
}
