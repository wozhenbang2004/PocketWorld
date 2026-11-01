package dev.pocketengine.features.remote;

import org.json.JSONObject;

/** Stateless validation/projection. Only the original TaskStore owns durable drafts/claims. */
public final class RemoteTaskDrafts {
    private RemoteTaskDrafts(){}
    public static JSONObject scope(JSONObject source,String profileGeneration,String mode)throws Exception {
        if(!mode.matches("steer|next_turn")||!profileGeneration.matches("[a-f0-9]{64}"))throw new SecurityException("原草稿目标或电脑代次无效");
        if(!source.optBoolean("protocolVerified")||!source.optString("worker").matches("claude|codex_app_server")||!(source.optString("worker").equals("claude")?"claude_stream_json":"codex_app_server").equals(source.optString("protocol")))throw new SecurityException("原执行器真实协议尚未确认");
        JSONObject scope=new JSONObject().put("mode",mode).put("profileGeneration",profileGeneration);
        for(String key:new String[]{"taskId","runId","coordinatorEpoch","profileId","serviceBase","jobId","jobOperationId","environmentId","worker","protocol","threadId"}){
            Object value=source.get(key);if(value==JSONObject.NULL||value instanceof String&&((String)value).isEmpty())throw new SecurityException("原草稿会话尚未确认");scope.put(key,value);
        }
        for(String key:new String[]{"sessionId","promptRequestId","workspaceIdentity"})if(source.has(key))scope.put(key,source.get(key));
        if(mode.equals("steer")){if(!source.optString("protocol").equals("codex_app_server"))throw new SecurityException("此执行器没有真实运行中steer协议，请排队到下一轮");scope.put("expectedTurnId",source.getString("turnId"));}
        return scope;
    }
    public static boolean same(JSONObject a,JSONObject b)throws Exception {
        if(a==null||b==null||a.length()!=b.length())return false;
        for(java.util.Iterator<String> keys=a.keys();keys.hasNext();){String key=keys.next();if(!a.get(key).equals(b.opt(key)))return false;}return true;
    }
    public static String text(String value){if(value==null||value.trim().isEmpty()||value.length()>12000||value.indexOf('\0')>=0)throw new IllegalArgumentException("草稿需为1至12000字符");return value;}
}
