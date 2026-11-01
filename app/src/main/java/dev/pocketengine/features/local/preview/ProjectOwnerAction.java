package dev.pocketengine.features.local.preview;

import java.util.Set;
import org.json.JSONObject;

/** Owner review of an existing tracked native action. This data grants no task/run authority. */
public final class ProjectOwnerAction {
    private ProjectOwnerAction(){}
    public static JSONObject reviewed(JSONObject plan,JSONObject step)throws Exception {
        JSONObject action=step.getJSONObject("hostAction"),payload=action.getJSONObject("payload");String command=action.getString("command");
        if(!"local".equals(action.optString("feature"))||!Set.of("termux.prepare","termux.probe","termux.run").contains(command))throw new SecurityException("方案不属于现有本机项目执行入口");
        if(!plan.getString("checkoutId").equals(payload.optString("checkoutId"))||(!command.equals("termux.probe")||payload.has("expectedSourceDigest"))&&!plan.getString("sourceDigest").equals(payload.optString("expectedSourceDigest")))throw new SecurityException("执行步骤与当前工作副本或源码版本不一致");
        if(payload.optJSONObject("projectPlan")==null||!step.optString("stepId",step.optString("id")).equals(payload.optString("projectStepId")))throw new SecurityException("缺少已核对方案与步骤身份");
        noAuthority(payload);
        return new JSONObject().put("command",command).put("payload",new JSONObject(payload.toString()));
    }
    public static JSONObject reviewedCollection(JSONObject selection,JSONObject build,String selectedPath)throws Exception {
        if(!ProjectPreviewArtifact.successfulBuild(build))throw new SecurityException("请先取得真实成功构建回执");
        ProjectPreviewArtifact.path(selectedPath);
        String digest=selection.getString("artifactPlanDigest");
        if(!digest.matches("[a-f0-9]{64}")||!digest.equals(selection.optString("artifactPlanId"))
                ||!build.getString("checkoutId").equals(selection.optString("checkoutId"))||!build.getString("sourceDigest").equals(selection.optString("sourceDigest"))
                ||!build.getString("jobId").equals(selection.optString("buildJobId"))||!build.getString("receiptIdentity").equals(selection.optString("buildReceiptIdentity"))
                ||!selectedPath.equals(selection.optString("path"))||ProjectPreviewArtifact.exactInteger(selection.opt("maxBytes"))!=32000)throw new SecurityException("回收选择与已显示构建、路径或大小不一致");
        JSONObject action=selection.getJSONObject("hostAction"),payload=action.getJSONObject("payload"),core=payload.getJSONObject("artifactSelection");
        if(!"local".equals(action.optString("feature"))||!"termux.collect".equals(action.optString("command"))
                ||!selection.getString("checkoutId").equals(payload.optString("checkoutId"))||!selection.getString("sourceDigest").equals(payload.optString("expectedSourceDigest")))throw new SecurityException("回收必须走原生tracked Termux入口");
        for(String key:new String[]{"checkoutId","sourceDigest","buildJobId","buildReceiptIdentity","path"})if(!selection.getString(key).equals(core.optString(key)))throw new SecurityException("原生回收载荷与已核对选择不一致");
        if(!digest.equals(core.optString("artifactPlanDigest"))||!digest.equals(core.optString("artifactPlanId"))||ProjectPreviewArtifact.exactInteger(core.opt("maxBytes"))!=32000)throw new SecurityException("回收载荷的方案或大小被替换");
        noAuthority(payload);noAuthority(core);
        return new JSONObject().put("command","termux.collect").put("payload",new JSONObject(payload.toString()));
    }
    private static void noAuthority(JSONObject value){for(String key:new String[]{"taskId","runId","_taskId","_runId","operationId","_operationId","principalId","spaceId","coordinatorEpoch","grant","approval"})if(value.has(key))throw new SecurityException("执行身份必须由原生宿主登记");}
}
