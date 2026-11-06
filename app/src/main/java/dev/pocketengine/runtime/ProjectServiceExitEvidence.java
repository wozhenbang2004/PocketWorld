package dev.pocketengine.runtime;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Original-run service exit proof shared by retained-development close and run cleanup.
 * This reader does not stop processes, change lifecycle state, or authorize a retry.
 * Runner owns when to request proof and how pending proof affects its final outcome.
 * Unavailable or incomplete evidence remains null/pending, never fabricated success.
 */
final class ProjectServiceExitEvidence {
    private ProjectServiceExitEvidence() { }

    /**
     * Post-cleanup audit, including after cancellation. Do not insert Control.check:
     * stopping admission does not erase the original process/lease exit facts.
     * The journal is the authority; a tool receipt or a successful Java close is
     * not evidence that the process group and its resources have been released.
     */
    static JSONObject verified(String taskId,RunControl original,RunJournal journal,java.util.List<String> operations){
        if(journal==null||original==null||operations.isEmpty()||operations.size()>400)return null;
        try{
            JSONObject proof=journal.projectServiceExitEvidence(new JSONArray(operations));
            if(proof==null||!"pocketworld.project-service-exits.v1".equals(proof.optString("format"))
                    ||!Boolean.TRUE.equals(proof.opt("allReleased"))||!taskId.equals(proof.optString("taskId"))
                    ||!original.matchesHostRun(taskId,proof.optString("runId"),proof.optInt("coordinatorEpoch",-1)))return null;
            // Require the complete requested set, not a successful subset. Keep
            // duplicate-row rejection and exact run/epoch association together.
            JSONArray rows=proof.optJSONArray("executions");if(rows==null||rows.length()!=operations.size())return null;
            Set<String> expected=new java.util.LinkedHashSet<>(operations),actual=new java.util.LinkedHashSet<>();
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);String id=row.optString("operationId");Object exit=row.opt("exitCode");
                if(!expected.contains(id)||!actual.add(id)||!id.equals(row.optString("executionId"))
                        ||!row.optString("status").matches("completed|failed")||!(exit instanceof Integer||exit instanceof Long)
                        ||!Boolean.TRUE.equals(row.opt("processExited"))||!Boolean.TRUE.equals(row.opt("processGroupAbsent"))
                        ||!Boolean.TRUE.equals(row.opt("resourcesReleased")))return null;
            }
            return actual.equals(expected)?new JSONObject(proof.toString()):null;
        }catch(Exception unavailable){return null;}
    }
    /** Presentation of already verified evidence only; never a new success verdict.
     * Process exit/resource release does not certify the user's business result. */
    static String describe(JSONObject proof)throws Exception {
        JSONArray rows=proof.getJSONArray("executions");StringBuilder message=new StringBuilder("原开发服务已取得真实退出、进程组与租约释放回执：");
        for(int i=0;i<Math.min(4,rows.length());i++){
            JSONObject row=rows.getJSONObject(i);if(i>0)message.append("；");
            message.append(row.getString("executionId")).append("（原状态 ").append(row.getString("status")).append("，退出码 ").append(row.get("exitCode")).append("）");
        }
        if(rows.length()>4)message.append("；其余原执行见收尾证据");
        return message.append("。模型上文的运行中描述只代表收尾前观察，不代表这些服务此刻仍在运行，也不代表业务验证成功。").toString();
    }

}
