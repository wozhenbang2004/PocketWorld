package dev.pocketengine.features.remote;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded protocol values only; no TaskStore/owner/dispatch authority is created here. */
public final class RemoteInteractions {
    private RemoteInteractions(){}
    public static JSONObject question(JSONObject source,String requestId,String requestHash)throws Exception {
        if(requestId==null||requestHash==null||!requestHash.matches("[a-f0-9]{64}"))throw new SecurityException("问题身份无效");
        JSONArray pending=source.optJSONArray("pendingQuestions");if(pending==null||pending.length()>12)throw new SecurityException("没有有界原问题");
        JSONObject question=null;for(int i=0;i<pending.length();i++){JSONObject value=pending.getJSONObject(i);if(requestId.equals(value.optString("requestId")))question=value;}
        if(question==null||!requestHash.equals(question.optString("requestHash"))||question.optInt("version")!=1||question.opt("canAnswer")!=Boolean.TRUE||question.opt("grantsPermissions")!=Boolean.FALSE||question.toString().getBytes(StandardCharsets.UTF_8).length>32768)throw new SecurityException("原问题已变化、不支持或不是正常问答");
        if(!source.optBoolean("protocolVerified")||!source.optString("protocol").equals(question.optString("protocol")))throw new SecurityException("问题的原协议身份未确认");
        String protocol=source.getString("protocol");
        if(protocol.equals("codex_app_server")){for(String key:new String[]{"threadId","turnId"})if(source.optString(key).trim().isEmpty()||!source.getString(key).equals(question.optString(key)))throw new SecurityException("问题不属于原thread/turn");if(question.optString("itemId").trim().isEmpty())throw new SecurityException("问题缺少原item");}
        else if(protocol.equals("claude_stream_json")){if(!source.optBoolean("sessionIdentityConfirmed")||!source.optBoolean("controlProtocolVerified"))throw new SecurityException("Claude问题控制协议未确认");for(String key:new String[]{"sessionId","promptRequestId"})if(source.optString(key).trim().isEmpty()||!source.getString(key).equals(question.optString(key)))throw new SecurityException("问题不属于原session/prompt");if(question.optString("toolUseId").trim().isEmpty()||!question.optString("method").equals("claude/AskUserQuestion"))throw new SecurityException("不是原AskUserQuestion请求");}
        else throw new SecurityException("此协议未支持正常问答");
        JSONArray questions=question.getJSONArray("questions");if(questions.length()<1||questions.length()>(protocol.equals("claude_stream_json")?4:8))throw new SecurityException("问题数超限");
        Set<String> ids=new HashSet<>();for(int i=0;i<questions.length();i++){JSONObject item=questions.getJSONObject(i);String id=bounded(item,"id",200),text=bounded(item,"text",4000);if(!ids.add(id)||!(item.opt("requiresPrivateInput") instanceof Boolean)||item.optBoolean("requiresPrivateInput")||!(item.opt("multiSelect") instanceof Boolean)||!(item.opt("allowOther") instanceof Boolean))throw new SecurityException("原问题字段不可安全回答");JSONArray options=item.getJSONArray("options");if(options.length()>20)throw new SecurityException("问题选项超限");Set<String> labels=new HashSet<>();for(int n=0;n<options.length();n++)if(!labels.add(bounded(options.getJSONObject(n),"label",300)))throw new SecurityException("重复选项无法唯一回答");}
        return new JSONObject(question.toString());
    }
    public static JSONObject answers(JSONObject question,JSONObject supplied)throws Exception {
        JSONArray questions=question.getJSONArray("questions");if(supplied==null||supplied.length()!=questions.length())throw new SecurityException("须回答每个原问题，不接受权限或其他字段");JSONObject result=new JSONObject();
        for(int i=0;i<questions.length();i++){JSONObject item=questions.getJSONObject(i);String id=item.getString("id");JSONArray values=supplied.optJSONArray(id);boolean multiple=item.getBoolean("multiSelect");if(values==null||values.length()<1||values.length()>(multiple?20:1))throw new SecurityException("答案数量不符原问题");Set<String> choices=new HashSet<>(),seen=new HashSet<>();JSONArray options=item.getJSONArray("options");for(int n=0;n<options.length();n++)choices.add(options.getJSONObject(n).getString("label"));JSONArray actual=new JSONArray();boolean custom=false;
            for(int n=0;n<values.length();n++){Object raw=values.get(n);if(!(raw instanceof String))throw new SecurityException("答案必须是原选项文字或有界自由文本");String value=(String)raw;if(value.trim().isEmpty()||value.length()>2000||value.indexOf('\0')>=0||!seen.add(value))throw new SecurityException("答案为空、超限或重复");custom|=!choices.contains(value);actual.put(value);}
            if(custom&&(!item.getBoolean("allowOther")||values.length()!=1))throw new SecurityException("原问题未允许此自由答，不能混入其他字段或选项");result.put(id,actual);
        }
        if(result.toString().getBytes(StandardCharsets.UTF_8).length>16384)throw new SecurityException("答案超出本次有界输入");return result;
    }
    public static JSONObject progress(JSONObject job)throws Exception {
        JSONObject value=job.optJSONObject("progress");if(value==null)return null;
        if(value.optInt("version")!=1||value.optBoolean("liveStreaming")||value.optBoolean("completeHistory")||value.toString().getBytes(StandardCharsets.UTF_8).length>32768||value.optJSONArray("events")==null||value.getJSONArray("events").length()>64)throw new SecurityException("执行进度不是支持的有界原job快照");return new JSONObject(value.toString());
    }
    private static String bounded(JSONObject object,String key,int limit)throws Exception {Object raw=object.opt(key);if(!(raw instanceof String)||((String)raw).trim().isEmpty()||((String)raw).length()>limit||((String)raw).indexOf('\0')>=0)throw new SecurityException("原问题字段无效："+key);return (String)raw;}
}
