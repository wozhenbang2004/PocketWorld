package dev.pocketengine.features.remote;

import org.json.JSONObject;

/** Stateless validation/projection. Only the original TaskStore owns durable drafts/claims. */
public final class RemoteTaskDrafts {
    private RemoteTaskDrafts(){}
    public static JSONObject scope(JSONObject source,String profileGeneration,String mode)throws Exception {
        if(!mode.matches("steer|next_turn")||!profileGeneration.matches("[a-f0-9]{64}"))throw new SecurityException("原草稿目标或电脑代次无效");
        String worker=source.optString("worker"),protocol=source.optString("protocol");boolean pi=worker.equals("pi")&&protocol.equals("pi_rpc_v1");
        if(!source.optBoolean("protocolVerified")||!RemoteHarnessSelection.isTeamWorker(worker)||!RemoteHarnessSelection.wireProtocol(worker).equals(protocol))throw new SecurityException("原执行器真实协议尚未确认");
        if(pi&&(source.opt("protocolVerified")!=Boolean.TRUE||!RemoteHarnessSelection.piScopeConfirmed(source)||!(source.opt("threadId") instanceof String)||source.getString("threadId").isEmpty()||!source.getString("threadId").equals(source.opt("sessionId"))||!(source.opt("promptRequestId") instanceof String)||source.getString("promptRequestId").isEmpty()))throw new SecurityException("Pi草稿仅属于已确认同session/thread的原无工具会话");
        JSONObject scope=new JSONObject().put("mode",mode).put("profileGeneration",profileGeneration);
        for(String key:new String[]{"taskId","runId","coordinatorEpoch","profileId","serviceBase","jobId","jobOperationId","environmentId","worker","protocol","threadId"}){
            Object value=source.get(key);if(value==JSONObject.NULL||value instanceof String&&((String)value).isEmpty())throw new SecurityException("原草稿会话尚未确认");scope.put(key,value);
        }
        for(String key:new String[]{"sessionId","promptRequestId","workspaceIdentity"})if(source.has(key))scope.put(key,source.get(key));
        if(pi){scope.put("noTools",source.getBoolean("noTools"));if(source.optBoolean("teamToolsOnly"))scope.put("teamContextIdentity",dev.pocketengine.store.TaskStore.capabilityFingerprint(source.getJSONObject("teamContext"))).put("teamToolsOnly",true);}
        if(RemoteHarnessSelection.isGenericWorker(worker)){if(!mode.equals("next_turn"))throw new SecurityException("此Harness未支持原turn追加");JSONObject selection=RemoteHarnessSelection.checked(source.optJSONObject("harnessSelection"),worker);scope.put("configurationRevision",selection.getString("configurationRevision")).put("capabilityRevision",selection.getString("capabilityRevision"));}
        if(mode.equals("steer"))RemoteMessageTarget.copy(RemoteMessageTarget.observed(source),scope);
        return scope;
    }
    public static boolean same(JSONObject a,JSONObject b)throws Exception {
        if(a==null||b==null||a.length()!=b.length())return false;
        for(java.util.Iterator<String> keys=a.keys();keys.hasNext();){String key=keys.next();if(!a.get(key).equals(b.opt(key)))return false;}return true;
    }
    public static String text(String value){if(value==null||value.trim().isEmpty()||value.length()>12000||value.indexOf('\0')>=0)throw new IllegalArgumentException("草稿需为1至12000字符");return value;}
}
