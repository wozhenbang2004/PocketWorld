package dev.pocketengine.features.plugins;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Request-scoped 2026-07-28 subscription correlation. Notifications confer no execution authority. */
final class McpSubscriptions {
    private static final String ID="io.modelcontextprotocol/subscriptionId";
    private static final Set<String> FLAGS=Set.of("toolsListChanged","promptsListChanged","resourcesListChanged");
    private final String id;
    private final JSONObject requested;
    private JSONObject acknowledged;
    private final JSONArray updates=new JSONArray();
    private boolean complete;

    McpSubscriptions(String id,JSONObject filter)throws Exception{this.id=id;requested=filter(filter);}
    static JSONObject filter(JSONObject input)throws Exception {
        JSONObject out=new JSONObject();
        for(var keys=input.keys();keys.hasNext();){String key=keys.next();
            if(FLAGS.contains(key)){
                if(!(input.get(key) instanceof Boolean))throw new IllegalArgumentException("订阅筛选需为布尔值");
                if(input.getBoolean(key))out.put(key,true);
            }else if(key.equals("resourceSubscriptions")){
                JSONArray raw=input.getJSONArray(key),uris=new JSONArray();Set<String> seen=new HashSet<>();
                if(raw.length()>32)throw new IllegalArgumentException("每次最多订阅32个已选资源");
                for(int i=0;i<raw.length();i++){Object value=raw.get(i);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>2048||((String)value).chars().anyMatch(c->c<32||c==127)||!seen.add((String)value))throw new IllegalArgumentException("订阅资源身份无效或重复");uris.put(value);}
                if(uris.length()>0)out.put(key,uris);
            }else throw new IllegalArgumentException("未支持的订阅筛选："+key);
        }
        return out;
    }
    JSONObject requested()throws Exception{return McpData.copy(requested);}
    boolean hasUpdates(){return updates.length()>0;}
    boolean complete(){return complete;}
    void accept(JSONObject message)throws Exception {
        if(complete||!"2.0".equals(message.optString("jsonrpc")))throw new SecurityException("订阅消息已结束或JSON-RPC身份无效");
        if(message.has("id")){
            if(!id.equals(message.opt("id"))||message.has("method"))throw new SecurityException("不是本次订阅的回执");
            if(message.has("error"))throw new IllegalArgumentException("服务未接受此订阅请求");
            JSONObject result=message.getJSONObject("result");requireId(result);
            if(acknowledged==null||!"complete".equals(result.optString("resultType")))throw new SecurityException("订阅尚未确认或结束回执无效");
            complete=true;return;
        }
        if(message.has("result")||message.has("error"))throw new SecurityException("订阅通知格式无效");
        String method=message.getString("method");JSONObject params=message.getJSONObject("params");requireId(params);
        if(method.equals("notifications/subscriptions/acknowledged")){
            if(acknowledged!=null)throw new SecurityException("订阅确认重复");
            JSONObject selected=filter(params.getJSONObject("notifications"));
            for(String flag:FLAGS)if(selected.optBoolean(flag)&&!requested.optBoolean(flag))throw new SecurityException("服务扩大了订阅范围");
            JSONArray uris=selected.optJSONArray("resourceSubscriptions");for(int i=0;uris!=null&&i<uris.length();i++)if(!contains(requested.optJSONArray("resourceSubscriptions"),uris.getString(i)))throw new SecurityException("服务确认了未选择的资源");
            acknowledged=selected;return;
        }
        if(acknowledged==null)throw new SecurityException("订阅确认之前收到更新");
        String flag=switch(method){case "notifications/tools/list_changed"->"toolsListChanged";case "notifications/prompts/list_changed"->"promptsListChanged";case "notifications/resources/list_changed"->"resourcesListChanged";case "notifications/resources/updated"->"resourceSubscriptions";default->throw new SecurityException("服务发出了未请求的订阅消息");};
        JSONObject update=new JSONObject().put("method",method);
        if(flag.equals("resourceSubscriptions")){
            String uri=params.getString("uri");if(!contains(acknowledged.optJSONArray(flag),uri))throw new SecurityException("资源更新不属于本次订阅");update.put("uri",uri);
        }else if(!acknowledged.optBoolean(flag))throw new SecurityException("更新类型未获订阅确认");
        if(updates.length()>=32)throw new IllegalArgumentException("订阅更新过多，请重新读取当前目录");updates.put(update);
    }
    private void requireId(JSONObject object)throws Exception{if(!id.equals(object.getJSONObject("_meta").opt(ID)))throw new SecurityException("旧订阅或其他连接的回调不可归入本次观察");}
    static boolean contains(JSONArray array,String value){for(int i=0;array!=null&&i<array.length();i++)if(value.equals(array.optString(i)))return true;return false;}
    JSONObject result(String reason)throws Exception{return new JSONObject().put("subscriptionId",id).put("requested",requested()).put("acknowledged",acknowledged==null?JSONObject.NULL:McpData.copy(acknowledged)).put("updates",new JSONArray(updates.toString())).put("status",complete?"closed":hasUpdates()?"update_received":"observation_ended").put("endReason",reason).put("gracefulClose",complete).put("automaticReconnect",false).put("automaticReplay",false).put("untrusted",true);}
}
