package dev.pocketengine.features.channels;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;
import org.json.JSONArray;

/** Protocol parsing, not identity policy. No caller-supplied role/space/grant is retained. */
public final class ChannelProtocol {
    private ChannelProtocol() { }
    public static boolean ed25519Available() {
        try{KeyFactory.getInstance("Ed25519");Signature.getInstance("Ed25519");return true;}catch(Exception unavailable){return false;}
    }
    public static JSONObject canonicalRelay(JSONObject body)throws Exception {
        JSONObject event=new JSONObject().put("messageId",ChannelCore.identifier(body,"messageId"))
                .put("senderId",ChannelCore.identifier(body,"senderId")).put("kind",body.optString("kind"))
                .put("text",ChannelCore.text(body,"text",4000,false)).put("sentAt",body.optLong("sentAt",System.currentTimeMillis()));
        if(body.optString("kind").equals("group"))event.put("roomId",ChannelCore.identifier(body,"roomId"));
        return event;
    }
    public static JSONObject canonicalQq(JSONObject envelope)throws Exception {return canonicalQq(envelope,"");}
    static JSONObject canonicalQq(JSONObject envelope,String nativeBotUserId)throws Exception {
        if(envelope.optInt("op",-1)!=0)throw new IllegalArgumentException("不是消息分发事件");
        String type=envelope.optString("t"),scene=QqConversation.fromEventType(type);boolean group=scene.equals(QqConversation.GROUP)||scene.equals(QqConversation.GUILD),guild=scene.equals(QqConversation.GUILD)||scene.equals(QqConversation.GUILD_DM);
        JSONObject body=envelope.getJSONObject("d"),author=body.getJSONObject("author");if(author.optBoolean("bot",false))throw new IllegalArgumentException("忽略机器人消息");
        String sender=QqConversation.nativeId(author,guild?"id":group?"member_openid":"user_openid");if(guild&&!nativeBotUserId.isEmpty()&&sender.equals(nativeBotUserId))throw new IllegalArgumentException("忽略当前原生机器人回声");
        JSONObject canonical=new JSONObject().put("messageId",QqConversation.nativeId(body,"id")).put("senderId",sender).put("conversationKind",scene).put("kind",group?"group":"dm").put("text",ChannelCore.text(body,"content",4000,true));
        if(guild){canonical.put("qqGuildId",QqConversation.nativeId(body,"guild_id"));if(group)canonical.put("qqChannelId",QqConversation.nativeId(body,"channel_id")).put("roomId",QqConversation.nativeId(body,"channel_id"));}
        else if(group)canonical.put("roomId",QqConversation.nativeId(body,"group_openid"));
        JSONObject parts=ChannelMessageParts.qq(body);if(blank(canonical.getString("text"))){String elementText=ChannelMessageParts.currentText(parts);if(!blank(elementText))canonical.put("text",elementText);else{if(!ChannelMessageParts.qqHasMedia(parts))throw new IllegalArgumentException("QQ消息没有可保留的正文或媒体项");canonical.put("nativeMediaOnly",true).put("partialInput",true);}}canonical.put("messageParts",parts);String commandText=ChannelMessageParts.qqCommandText(body);if(scene.equals(QqConversation.GUILD)&&!nativeBotUserId.isEmpty()){for(String tag:new String[]{"<@"+nativeBotUserId+">","<@!"+nativeBotUserId+">"})if(commandText.startsWith(tag))commandText=commandText.substring(tag.length()).trim();}if(!blank(commandText))canonical.put("text",commandText);
        if(group){
            JSONArray incoming=new JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();JSONArray direct=body.optJSONArray("attachments");
            if(direct!=null){JSONArray safe=ChannelMessageParts.attachments(direct,"attachments");for(int i=0;i<safe.length();i++){JSONObject attachment=safe.getJSONObject(i);String key=attachment.getString("url")+"\u0000"+attachment.getLong("bytes");if(seen.add(key))incoming.put(attachment);}}
            JSONArray images=ChannelMessageParts.qqImages(parts);for(int i=0;i<images.length();i++){JSONObject image=images.getJSONObject(i);String key=image.getString("url")+"\u0000"+image.getLong("bytes");if(seen.add(key))incoming.put(image);else for(int j=0;j<incoming.length();j++)if(incoming.getJSONObject(j).getString("url").equals(image.getString("url")))incoming.getJSONObject(j).put("partIndex",image.getInt("partIndex"));}
            if(incoming.length()>8)throw new IllegalArgumentException("当前消息与引用附件合计最多8项");if(incoming.length()>0)canonical.put("groupAttachments",incoming);
        }

        try{canonical.put("sentAt",Instant.parse(body.getString("timestamp")).toEpochMilli());}
        catch(Exception malformed){throw new IllegalArgumentException("QQ消息时间格式无效");}
        return canonical;
    }
    public static void authenticateRelay(String secret,Map<String,String> headers)throws Exception {
        byte[] expected=("Bearer "+secret).getBytes(StandardCharsets.UTF_8);
        byte[] actual=headers.getOrDefault("authorization","").getBytes(StandardCharsets.UTF_8);
        if(secret.length()<32||!MessageDigest.isEqual(expected,actual))throw new SecurityException("消息桥接凭据无效");
    }
    public static void authenticateQq(String appId,String secret,Map<String,String> headers,byte[] body,long now)throws Exception {
        if(!appId.equals(headers.getOrDefault("x-bot-appid","")))throw new SecurityException("QQ AppID不匹配");
        String timestamp=headers.getOrDefault("x-signature-timestamp","");
        if(!timestamp.matches("[0-9]{10,13}"))throw new SecurityException("QQ签名时间无效");
        long epoch=Long.parseLong(timestamp);if(timestamp.length()>10)epoch/=1000;
        if(Math.abs(now/1000-epoch)>300)throw new SecurityException("QQ回调时间已过期");
        byte[] supplied=unhex(headers.getOrDefault("x-signature-ed25519",""));
        if(supplied.length!=64)throw new SecurityException("QQ签名格式无效");
        byte[] prefix=timestamp.getBytes(StandardCharsets.UTF_8),signed=new byte[prefix.length+body.length];
        System.arraycopy(prefix,0,signed,0,prefix.length);System.arraycopy(body,0,signed,prefix.length,body.length);
        // Ed25519 is deterministic: using the shared Bot Secret seed, the standard provider
        // computes the expected signature. No custom curve arithmetic or public-key derivation.
        byte[] expected=sign(secret,signed);
        if(!MessageDigest.isEqual(expected,supplied))throw new SecurityException("QQ消息签名不匹配");
    }
    public static JSONObject qqChallenge(String secret,JSONObject envelope)throws Exception {
        JSONObject data=envelope.getJSONObject("d");String plain=ChannelCore.text(data,"plain_token",1024,false);
        String timestamp=ChannelCore.text(data,"event_ts",32,false);
        if(!plain.matches("[A-Za-z0-9._-]{1,256}")||!timestamp.matches("[0-9]{10}"))
            throw new SecurityException("回调验证参数格式无效");
        if(Math.abs(System.currentTimeMillis()/1000-Long.parseLong(timestamp))>300)throw new SecurityException("回调验证时间已过期");
        return new JSONObject().put("plain_token",plain).put("signature",hex(sign(secret,(timestamp+plain).getBytes(StandardCharsets.UTF_8))));
    }
    static byte[] sign(String secret,byte[] message)throws Exception {
        byte[] raw=secret.getBytes(StandardCharsets.UTF_8);if(raw.length==0)throw new SecurityException("QQ凭据未设置");
        byte[] seed=new byte[32];for(int i=0;i<seed.length;i++)seed[i]=raw[i%raw.length];
        // RFC8410 PKCS#8 envelope for a 32-byte RFC8032 seed, produced directly for the JCA API.
        byte[] prefix=unhex("302e020100300506032b657004220420"),encoded=new byte[prefix.length+seed.length];
        System.arraycopy(prefix,0,encoded,0,prefix.length);System.arraycopy(seed,0,encoded,prefix.length,seed.length);
        try {
            PrivateKey key=KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
            Signature signer=Signature.getInstance("Ed25519");signer.initSign(key);signer.update(message);return signer.sign();
        } finally {java.util.Arrays.fill(seed,(byte)0);java.util.Arrays.fill(encoded,(byte)0);java.util.Arrays.fill(raw,(byte)0);}
    }
    static byte[] unhex(String value)throws Exception {
        if((value.length()&1)!=0||value.length()>256||!value.matches("[0-9a-fA-F]*"))throw new SecurityException("签名编码无效");
        byte[] out=new byte[value.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return out;
    }
    private static boolean blank(String value){for(int i=0;i<value.length();i++)if(!Character.isWhitespace(value.charAt(i)))return false;return true;}
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
