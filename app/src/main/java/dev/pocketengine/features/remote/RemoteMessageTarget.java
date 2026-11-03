package dev.pocketengine.features.remote;

import org.json.JSONObject;

/** Exact protocol identity only. Native TaskStore claims still authorize dispatch. */
public final class RemoteMessageTarget {
    private RemoteMessageTarget(){}
    public static boolean pi(JSONObject value){return value!=null&&"pi_rpc_v1".equals(value.optString("protocol"));}

    public static JSONObject observed(JSONObject source)throws Exception {
        if(source==null)throw new SecurityException("原执行器尚无输入身份");
        JSONObject target=new JSONObject();String protocol=source.optString("protocol");
        if("codex_app_server".equals(protocol))return target.put("expectedTurnId",text(source,"turnId"));
        if(!"pi_rpc_v1".equals(protocol)||!"pi".equals(source.optString("worker"))
                ||!Boolean.TRUE.equals(source.opt("protocolVerified"))||!Boolean.TRUE.equals(source.opt("noTools"))
                ||!Boolean.TRUE.equals(source.opt("inputSteeringVerified"))||source.has("turnId"))
            throw new SecurityException("原执行器没有已确认的运行中输入协议");
        String session=text(source,"sessionId");if(!session.equals(text(source,"threadId")))throw new SecurityException("Pi原session/thread不一致");
        return target.put("protocol","pi_rpc_v1").put("sessionId",session).put("expectedPromptRequestId",text(source,"promptRequestId"));
    }
    public static JSONObject copy(JSONObject source,JSONObject destination)throws Exception {
        if(pi(source)){
            if(source.has("expectedTurnId")||source.has("turnId"))throw new SecurityException("Pi队列输入不能冒充turn输入");
            destination.put("protocol","pi_rpc_v1").put("sessionId",text(source,"sessionId"))
                    .put("expectedPromptRequestId",text(source,"expectedPromptRequestId"));
        }else{
            if(source.has("expectedPromptRequestId")||source.has("protocol")&&!"codex_app_server".equals(source.optString("protocol")))
                throw new SecurityException("输入目标协议不一致");
            destination.put("expectedTurnId",text(source,"expectedTurnId"));
        }
        return destination;
    }
    public static boolean same(JSONObject expected,JSONObject supplied)throws Exception {
        if(expected==null||supplied==null||pi(expected)!=pi(supplied))return false;
        JSONObject a=copy(expected,new JSONObject()),b=copy(supplied,new JSONObject());
        for(java.util.Iterator<String> keys=a.keys();keys.hasNext();){String key=keys.next();if(!a.get(key).equals(b.opt(key)))return false;}
        return true;
    }
    public static boolean receiptMatches(JSONObject expected,JSONObject receipt)throws Exception {
        if(!same(expected,receipt))return false;
        if(!"accepted".equals(receipt.optString("status")))return true;
        if(pi(expected))return !receipt.has("turnId")&&"queued".equals(receipt.optString("disposition"))
                &&"native_queue".equals(receipt.optString("delivery"))&&"unknown".equals(receipt.optString("consumed"));
        return expected.getString("expectedTurnId").equals(receipt.optString("turnId"));
    }
    private static String text(JSONObject value,String key)throws Exception {
        Object raw=value.opt(key);if(!(raw instanceof String)||((String)raw).trim().isEmpty()||((String)raw).length()>200||((String)raw).indexOf('\0')>=0)
            throw new SecurityException("原输入身份缺失或无效："+key);
        return (String)raw;
    }
}
