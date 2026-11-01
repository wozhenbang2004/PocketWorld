package dev.pocketengine.features.remote;

import org.json.JSONObject;

/** Schema/binding check on an already authenticated original job observation, NOT an authority
 * factory. TaskStore still compares its original task/run/receipt, and native owner review
 * separately approves a NEW continuation scope/timeout. No historical output is reconstructed. */
public final class RemoteColdRecovery {
    public static final String PROTOCOL="codex_stored_turn_recovery_v1";
    private RemoteColdRecovery(){}
    public static boolean receipt(JSONObject actual){return matches(actual,false);}
    public static boolean source(JSONObject source){return matches(source,true);}
    private static boolean matches(JSONObject value,boolean source){
        try{
            if(value==null||!"interrupted".equals(value.getString(source?"executorStatus":"status"))||!"codex_app_server".equals(value.getString("worker"))||!"codex_app_server".equals(value.getString("protocol"))||value.opt("protocolVerified")!=Boolean.TRUE)return false;
            JSONObject proof=value.getJSONObject("recoveryProof");
            if(!PROTOCOL.equals(proof.getString("format"))||!proof.getString("proofId").matches("[a-f0-9-]{36}")||!proof.getString("environmentConfigHash").matches("[a-f0-9]{64}")||!proof.getString("processIdentityHash").matches("[a-f0-9]{64}")||proof.getLong("checkedAt")<=0)return false;
            if(!value.getString(source?"jobId":"id").equals(proof.getString("jobId"))||!value.getString(source?"jobOperationId":"operationId").equals(proof.getString("jobOperationId")))return false;
            for(String key:new String[]{"environmentId","worker","threadId","turnId","workspaceIdentity"})if(!value.get(key).equals(proof.get(key)))return false;
            return proof.opt("processGroupStoppedVerified")==Boolean.TRUE&&proof.opt("storedLatestTurnVerified")==Boolean.TRUE&&proof.opt("readOnlyInspection")==Boolean.TRUE&&proof.opt("turnStartedByInspection")==Boolean.FALSE
                    &&proof.opt("internalUsageKnown")==Boolean.FALSE&&proof.opt("businessSuccessVerified")==Boolean.FALSE&&proof.getString("observedTurnStatus").matches("completed|failed|interrupted")&&proof.getString("observedThreadStatus").matches("notLoaded|idle");
        }catch(Exception unsupported){return false;}
    }
    /** Called only after the original SQL has revalidated its current source. */
    public static JSONObject ownerBudget(JSONObject source,JSONObject input)throws Exception {
        if(!source(source))throw new SecurityException("原中断job尚无准确进程和终态turn的只读核对");
        if(input==null||input.opt("recoveryOwnerAcknowledged")!=Boolean.TRUE)throw new SecurityException("继续冷中断会话前须明确批准本次新执行时限及内部费用未知风险");
        Object raw=input.opt("timeoutSeconds");int seconds=input.optInt("timeoutSeconds",-1);
        if(!(raw instanceof Number)||((Number)raw).doubleValue()!=seconds||seconds<10||seconds>900)throw new SecurityException("本次新执行时限需为10至900秒整数");
        return new JSONObject().put("recoveryProofId",source.getJSONObject("recoveryProof").getString("proofId")).put("recoveryOwnerAcknowledged",true).put("timeoutSeconds",seconds);
    }
}
