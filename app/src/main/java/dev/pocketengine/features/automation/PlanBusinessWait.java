package dev.pocketengine.features.automation;

import java.util.Set;
import org.json.JSONObject;

/** Exact retained native-channel location. JSON is a locator, never authenticated input or a grant. */
public final class PlanBusinessWait {
    private PlanBusinessWait(){}
    private static final String[] FIELDS={"eventId","fingerprint","payloadVersion","payloadSha256","provider","profileId","appId","transportEpoch","conversationKind","conversationId","conversationParentId","providerActorId","ownerId","principalId","spaceId","bindingEpoch","privateOwner"};
    private static final Set<String> ANCHOR=Set.of(FIELDS);
    public static JSONObject reference(JSONObject originalNativeMetadata)throws Exception {
        JSONObject anchor=new JSONObject();for(String key:FIELDS)anchor.put(key,originalNativeMetadata.get(key));
        return standard(new JSONObject().put("kind","authenticated_channel_inbound").put("anchor",anchor));
    }
    public static JSONObject standard(JSONObject supplied)throws Exception {
        if(supplied==null)return null;
        keys(supplied,Set.of("kind","anchor"));if(!"authenticated_channel_inbound".equals(supplied.opt("kind")))throw new IllegalArgumentException("仅支持原认证渠道入站事件等待");
        JSONObject raw=supplied.getJSONObject("anchor");keys(raw,ANCHOR);JSONObject anchor=new JSONObject();
        for(String key:FIELDS){
            if(key.equals("privateOwner")){if(!(raw.opt(key) instanceof Boolean))throw new IllegalArgumentException("原受众类型未知");anchor.put(key,raw.getBoolean(key));continue;}
            Object value=raw.opt(key);if(!(value instanceof String))throw new IllegalArgumentException("原事件定位字段缺失");String text=(String)value;
            if(text.length()>240||!Set.of("appId","conversationParentId","bindingEpoch").contains(key)&&text.isEmpty()||text.matches(".*[\\p{Cntrl}].*"))throw new IllegalArgumentException("原事件定位字段不在有界范围");
            for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))throw new IllegalArgumentException("原事件定位Unicode无效");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("原事件定位Unicode无效");}
            if(Set.of("fingerprint","payloadSha256").contains(key)&&!text.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("原事件缺准确hash");anchor.put(key,text);
        }
        if(!"original_channel_inbound_v1".equals(anchor.getString("payloadVersion")))throw new IllegalArgumentException("未知原入站载荷版本；不是provider序号");
        return new JSONObject().put("kind","authenticated_channel_inbound").put("anchor",anchor);
    }
    public static boolean same(JSONObject a,JSONObject b)throws Exception {
        JSONObject first=standard(a),second=standard(b);if(first==null||second==null)return first==second;
        for(String key:FIELDS)if(!first.getJSONObject("anchor").get(key).equals(second.getJSONObject("anchor").get(key)))return false;return true;
    }
    public static boolean sameAudience(JSONObject reference,JSONObject actualNativeMetadata)throws Exception {
        JSONObject anchor=standard(reference).getJSONObject("anchor");
        for(String key:FIELDS)if(!Set.of("eventId","fingerprint","payloadSha256").contains(key)&&!anchor.get(key).equals(actualNativeMetadata.opt(key)))return false;
        return true;
    }
    public static String anchorId(JSONObject reference)throws Exception{return standard(reference).getJSONObject("anchor").getString("eventId");}
    private static void keys(JSONObject value,Set<String> expected){if(value==null||value.length()!=expected.size())throw new IllegalArgumentException("原入站等待定位字段不完整");for(var it=value.keys();it.hasNext();)if(!expected.contains(it.next()))throw new IllegalArgumentException("等待条件不能携带正文、权限或任意任务身份");}
}
