package dev.pocketengine.features.remote;

import org.json.JSONObject;

/** Validates an owner-configured worker selection. It never contains launch commands or credentials. */
public final class RemoteHarnessSelection {
    public static final String PROTOCOL = "pocketworld.harness-selection.v1";
    private RemoteHarnessSelection() {}

    public static boolean isGenericWorker(String worker) {
        return worker != null && (worker.equals("muse_msp") || worker.matches("acp_[a-z][a-z0-9_-]{0,64}"));
    }
    public static boolean isTeamWorker(String worker) {
        return "codex_app_server".equals(worker) || "claude".equals(worker) || "pi".equals(worker) || isGenericWorker(worker);
    }
    public static boolean isProductionWorker(String worker) {
        return "codex_app_server".equals(worker) || "claude".equals(worker) || isGenericWorker(worker);
    }
    public static String wireProtocol(String worker) {
        if (isGenericWorker(worker)) return worker.equals("muse_msp") ? "muse_msp_v1" : "acp_v1";
        return "claude".equals(worker) ? "claude_stream_json" : "pi".equals(worker) ? "pi_rpc_v1" : "codex_app_server";
    }
    /** Pi either has its original empty tool set, or only this original host's bounded team bridge. */
    public static boolean piScopeConfirmed(JSONObject receipt) {
        if(receipt==null)return false;
        if(receipt.opt("noTools")==Boolean.TRUE)return receipt.opt("teamTools")!=Boolean.TRUE
                &&receipt.opt("teamToolsOnly")!=Boolean.TRUE&&receipt.opt("codingToolsSupported")!=Boolean.TRUE;
        if(receipt.opt("noTools")!=Boolean.FALSE||receipt.opt("teamTools")!=Boolean.TRUE
                ||receipt.opt("teamToolsOnly")!=Boolean.TRUE||receipt.opt("codingToolsSupported")!=Boolean.FALSE)return false;
        JSONObject context=receipt.optJSONObject("teamContext");
        if(context==null||!"pocketworld.team-tools.v1".equals(context.optString("protocol"))||context.length()!=9)return false;
        for(String key:new String[]{"runId","hostTaskId","memberId","turnId"})if(!(context.opt(key) instanceof String)||context.optString(key).isEmpty())return false;
        for(String key:new String[]{"round","generation","runGeneration"})if(!(context.opt(key) instanceof Integer||context.opt(key) instanceof Long)||context.optLong(key)<(key.equals("round")?1:0))return false;
        org.json.JSONArray roster=context.optJSONArray("roster");return roster!=null&&roster.length()>0&&roster.length()<=32;
    }
    public static JSONObject checked(JSONObject selection, String worker) throws Exception {
        if (!isGenericWorker(worker) || selection == null || selection.length() != 5
                || !PROTOCOL.equals(selection.optString("protocol")) || !worker.equals(selection.optString("workerId"))
                || selection.opt("ownerManagedExecution") != Boolean.TRUE
                || !selection.optString("configurationRevision").matches("[a-f0-9]{64}")
                || !selection.optString("capabilityRevision").matches("[a-f0-9]{64}")) {
            throw new SecurityException("请选择当前实际探测过的主人配置 Harness，不能提供启动命令或推测能力");
        }
        return new JSONObject(selection.toString());
    }
    public static JSONObject fromProbe(JSONObject probe, String worker) throws Exception {
        if (!worker.equals(probe.optString("worker")) || probe.opt("available") != Boolean.TRUE
                || probe.opt("protocolVerified") != Boolean.TRUE || !wireProtocol(worker).equals(probe.optString("protocol"))
                || !"owner_managed".equals(probe.optString("executionPolicy"))) {
            throw new SecurityException("所选 Harness 未取得实际协议握手与主人配置证明");
        }
        return checked(new JSONObject().put("protocol", PROTOCOL).put("workerId", worker)
                .put("configurationRevision", probe.get("configurationRevision"))
                .put("capabilityRevision", probe.get("capabilityRevision"))
                .put("ownerManagedExecution", true), worker);
    }
    public static void requireSame(JSONObject expected, JSONObject actual, String worker) throws Exception {
        JSONObject a = checked(expected, worker), b = checked(actual, worker);
        for (String key : new String[]{"protocol", "workerId", "configurationRevision", "capabilityRevision", "ownerManagedExecution"})
            if (!a.get(key).equals(b.get(key))) throw new SecurityException("原 Harness 配置或握手能力版本已变化");
    }
    public static boolean terminalConfirmed(JSONObject receipt, String worker) {
        return isGenericWorker(worker) && worker.equals(receipt.optString("worker"))
                && wireProtocol(worker).equals(receipt.optString("protocol")) && receipt.opt("protocolVerified") == Boolean.TRUE
                && receipt.opt("promptTerminalConfirmed") == Boolean.TRUE && receipt.opt("nativeSettled") == Boolean.TRUE
                && !receipt.optString("sessionId").isEmpty() && receipt.optString("sessionId").equals(receipt.optString("threadId"))
                && !receipt.optString("promptRequestId").isEmpty();
    }
    public static void requireResume(JSONObject receipt, JSONObject selection, String worker) throws Exception {
        if (!terminalConfirmed(receipt, worker) || receipt.opt("resumeSupported") != Boolean.TRUE
                || !receipt.optString("status", receipt.optString("executorStatus")).matches("completed|failed|cancelled"))
            throw new SecurityException("原 Harness 没有已结算会话及实际 session/load 能力，不能猜测续接");
        requireSame(selection, receipt.optJSONObject("harnessSelection"), worker);
    }
    public static JSONObject checkedApproval(JSONObject source, JSONObject pending) throws Exception {
        String worker=source.optString("worker"),protocol=wireProtocol(worker);
        checked(source.optJSONObject("harnessSelection"),worker);
        if(pending==null||source.opt("protocolVerified")!=Boolean.TRUE||!protocol.equals(source.optString("protocol"))
                ||!protocol.equals(pending.optString("protocol"))||pending.optString("requestId").isEmpty()
                ||pending.optString("requestId").length()>200||!pending.optString("requestHash").matches("[a-f0-9]{64}")
                ||!(pending.opt("canAccept") instanceof Boolean)
                ||!pending.optString("method").equals(worker.equals("muse_msp")?"msp/approval/request":"acp/session/request_permission"))
            throw new SecurityException("未取得原 Harness 的准确一次权限请求");
        for(String key:new String[]{"sessionId","promptRequestId"})if(source.optString(key).isEmpty()||!source.getString(key).equals(pending.optString(key)))throw new SecurityException("权限请求不属于原 session/prompt");
        return new JSONObject(pending.toString());
    }
    static void approvalFields(JSONObject input,JSONObject body) throws Exception {
        if(!isGenericWorker(input.optString("worker")))return;
        JSONObject pending=null;org.json.JSONArray rows=input.optJSONArray("pendingApprovals");
        for(int i=0;rows!=null&&i<rows.length();i++)if(input.getString("approvalRequestId").equals(rows.getJSONObject(i).optString("requestId"))){if(pending!=null)throw new SecurityException("原权限请求身份重复");pending=rows.getJSONObject(i);}
        if(pending!=null)checkedApproval(input,pending);
        for(String key:new String[]{"sessionId","promptRequestId","requestHash"}){
            Object value=pending==null?input.opt(key):pending.opt(key);if(!(value instanceof String)||((String)value).isEmpty())throw new SecurityException("原审批缺少准确 session/prompt/hash");body.put(key,value);
        }
        body.put("jobOperationId",input.getString("jobOperationId")).put("harnessSelection",checked(input.optJSONObject("harnessSelection"),input.getString("worker")));
    }
    static void bindProduction(JSONObject probe, JSONObject selected, String worker, boolean acknowledged) throws Exception {
        if (!isProductionWorker(worker) || probe.opt("available") != Boolean.TRUE) throw new SecurityException("此执行器没有真实远端制作能力");
        if (worker.equals("codex_app_server")) {
            if (!probe.optBoolean("protocolVerified") || !probe.optBoolean("accountConfigured") || !probe.optBoolean("selectedModelAvailable"))
                throw new SecurityException("原 Codex 协议、账号或所选模型未通过探测");
        } else {
            JSONObject capabilities = probe.optJSONObject("capabilities");
            if (!acknowledged || capabilities == null || capabilities.opt("projectProduction") != Boolean.TRUE
                    || capabilities.opt("assignmentWorkspace") != Boolean.TRUE)
                throw new SecurityException("此执行器未声明原独立副本制作能力，或尚未确认实际执行风险");
            selected.put("externalExecutionRiskAcknowledged", true);
            if (isGenericWorker(worker)) selected.put("harnessSelection", fromProbe(probe, worker));
            else if (!probe.optBoolean("cliFlagsVerified")) throw new SecurityException("原 Claude 执行契约尚未通过探测");
        }
    }
    static void requireProductionWorker(JSONObject environment,String capability,String worker)throws Exception {
        if("codex_app_server".equals(worker))return;
        JSONObject capabilities=environment.optJSONObject(capability);
        org.json.JSONArray workers=capabilities==null?null:capabilities.optJSONArray("workers");
        for(int i=0;workers!=null&&i<workers.length();i++)if(worker.equals(workers.optString(i)))return;
        throw new SecurityException("原环境没有为此执行器接通独立制作副本");
    }
}
