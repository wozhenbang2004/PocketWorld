package dev.pocketengine.features.remote;

import org.json.JSONArray;
import org.json.JSONObject;

/** The Companion's conditional mutation contract; a legacy job-id-only cancel is not equivalent. */
final class RemoteOriginalCancellation {
    static final String PROTOCOL="pocketworld.original-job-cancel.v1";
    private RemoteOriginalCancellation(){}
    static void requireSupport(JSONObject status)throws Exception {
        JSONObject capability=status.optJSONObject("originalJobCancellation");JSONArray required=capability==null?null:capability.optJSONArray("requiredFields");
        if(capability==null||!Boolean.TRUE.equals(capability.opt("supported"))||!PROTOCOL.equals(capability.optString("protocol"))
                ||!"/v1/jobs/{jobId}/cancel-original".equals(capability.optString("endpoint"))
                ||!"locked_job_operation_before_mutation".equals(capability.optString("identityCheck"))
                ||!"job_manager_cli_jobs_only".equals(capability.optString("scope"))
                ||required==null||required.length()!=1||!"jobOperationId".equals(required.optString(0)))
            throw new SecurityException("原电脑服务未证明支持按原操作条件停止；未发送停止请求，请核对原任务");
    }
    static void requireReceipt(JSONObject target,JSONObject result)throws Exception {
        String job=target.getString("jobId"),operation=target.getString("jobOperationId");JSONObject proof=result.optJSONObject("originalCancellation");
        if(!job.equals(result.optString("id"))||!operation.equals(result.optString("operationId"))
                ||!target.getString("serviceBase").equals(result.optString("serviceBase"))
                ||proof==null||!PROTOCOL.equals(proof.optString("protocol"))||!job.equals(proof.optString("jobId"))
                ||!operation.equals(proof.optString("jobOperationId"))||!Boolean.TRUE.equals(proof.opt("identityMatched"))||!Boolean.FALSE.equals(proof.opt("effectsReversed")))
            throw new SecurityException("原停止回执未确认；结果未知，请核对原任务，不自动重试");
    }
}
