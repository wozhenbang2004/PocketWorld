package dev.pocketengine.features.channels;

import org.json.JSONObject;

/** Official QQ scene ids are not interchangeable, even when their raw strings happen to match.
 * Parsing a scene is not owner binding, group registration, or permission to send a message. */
final class QqConversation {
    static final String C2C="qq_c2c", GROUP="qq_group", GUILD="qq_guild_channel", GUILD_DM="qq_guild_dm";
    private QqConversation() { }
    static String fromEventType(String type){
        switch(type){case "C2C_MESSAGE_CREATE":return C2C;case "GROUP_AT_MESSAGE_CREATE":case "GROUP_MESSAGE_CREATE":return GROUP;case "AT_MESSAGE_CREATE":return GUILD;case "DIRECT_MESSAGE_CREATE":return GUILD_DM;default:throw new IllegalArgumentException("非已支持的QQ消息场景");}
    }
    static String scene(JSONObject event){String value=event.optString("conversationKind");if(value.isEmpty())return event.optString("kind").equals("group")?GROUP:C2C;if(!value.matches("qq_c2c|qq_group|qq_guild_channel|qq_guild_dm"))throw new SecurityException("未知QQ会话场景");return value;}
    static boolean guild(JSONObject event){return scene(event).equals(GUILD)||scene(event).equals(GUILD_DM);}
    static boolean privateScene(JSONObject event){return scene(event).equals(C2C)||scene(event).equals(GUILD_DM);}
    /** Native platform fields, never an arbitrary endpoint from message text or metadata. */
    static String endpoint(JSONObject event)throws Exception {
        switch(scene(event)){
            case C2C:return "/v2/users/"+ChannelCore.identifier(event,"senderId")+"/messages";
            case GROUP:return "/v2/groups/"+ChannelCore.identifier(event,"roomId")+"/messages";
            case GUILD:return "/channels/"+ChannelCore.identifier(event,"qqChannelId")+"/messages";
            case GUILD_DM:return "/dms/"+ChannelCore.identifier(event,"qqGuildId")+"/messages";
            default:throw new SecurityException("QQ目标场景不可派发");
        }
    }
    static String nativeId(JSONObject value,String key)throws Exception {if(!(value.opt(key) instanceof String))throw new IllegalArgumentException("QQ原生身份须为无损字符串: "+key);String original=value.getString(key),checked=ChannelCore.identifier(value,key);if(!original.equals(checked))throw new IllegalArgumentException("QQ原生身份不可归一化或裁剪: "+key);return checked;}
    static String groupScene(JSONObject event){return scene(event).equals(GUILD)?GUILD:"group";}
    static boolean same(JSONObject a,JSONObject b){
        if(!scene(a).equals(scene(b)))return false;
        if(guild(a)&&(!a.optString("qqGuildId").equals(b.optString("qqGuildId"))||scene(a).equals(GUILD)&&!a.optString("qqChannelId").equals(b.optString("qqChannelId"))))return false;
        return true;
    }
    static JSONObject copyNative(JSONObject original,JSONObject target)throws Exception {
        String scene=scene(original);target.put("conversationKind",scene);
        if(scene.equals(GUILD)||scene.equals(GUILD_DM))target.put("qqGuildId",ChannelCore.identifier(original,"qqGuildId"));
        if(scene.equals(GUILD))target.put("qqChannelId",ChannelCore.identifier(original,"qqChannelId"));
        return target;
    }
}
