package dev.pocketengine.features.channels;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Authenticated protocol data only. Ordered source positions are evidence, never roles or tool grants. */
public final class ChannelMessageParts {
    private static final int MAX_PARTS=64;
    private static final Pattern MENTION=Pattern.compile("<@!?([A-Za-z0-9_.:@-]{1,180})>");
    private ChannelMessageParts() { }
    public static JSONObject qq(JSONObject body)throws Exception {
        String message=ChannelCore.identifier(body,"id");JSONArray parts=new JSONArray();String ref=index(body,"ref_msg_idx"),own=index(body,"msg_idx");
        JSONObject scene=body.optJSONObject("message_scene");JSONArray ext=scene==null?null:scene.optJSONArray("ext");
        for(int i=0;ext!=null&&i<ext.length()&&i<30;i++){Object raw=ext.opt(i);if(!(raw instanceof String)||((String)raw).length()>1024)continue;String value=(String)raw;int equals=value.indexOf('=');if(equals<0)continue;String key=value.substring(0,equals).trim(),item=value.substring(equals+1).trim();if(!validIndex(item))continue;if(key.equals("ref_msg_idx"))ref=item;else if(key.equals("msg_idx"))own=item;}
        JSONArray elements=body.optJSONArray("msg_elements");if(elements!=null&&elements.length()>30)throw new IllegalArgumentException("QQ消息元素超过30项");
        if(ref.isEmpty()&&number(body.opt("message_type"),103)&&elements!=null)for(int i=0;i<elements.length();i++){JSONObject element=elements.optJSONObject(i);if(element!=null&&!index(element,"msg_idx").isEmpty()){ref=index(element,"msg_idx");break;}}
        if(!ref.isEmpty()){
            JSONObject quote=part(parts,"quote","qq",message,"message_scene.ext",-1).put("quoteRef",ref).put("availability","unavailable");
            for(int i=0;elements!=null&&i<elements.length();i++){JSONObject element=elements.optJSONObject(i);if(element==null||!ref.equals(index(element,"msg_idx")))continue;
                quote.put("availability","native_payload").put("text",bounded(element.optString("content"),4000)).put("source",source("qq",message,"msg_elements",i));
                JSONArray quoted=element.optJSONArray("attachments");if(quoted!=null){JSONArray attachments=attachments(quoted,"msg_elements/"+i+"/attachments");quote.put("attachments",attachments);}break;
            }
        }
        String content=body.optString("content");if(content.length()>4000)throw new IllegalArgumentException("QQ正文超过4000字符");Matcher matches=MENTION.matcher(content);int offset=0;Set<String> inline=new HashSet<>();
        while(matches.find()){
            if(matches.start()>offset)part(parts,"text","qq",message,"content",offset).put("text",content.substring(offset,matches.start()));
            part(parts,"mention","qq",message,"content",matches.start()).put("mentionId",matches.group(1)).put("positionKnown",true);inline.add(matches.group(1));offset=matches.end();
        }
        if(offset<content.length())part(parts,"text","qq",message,"content",offset).put("text",content.substring(offset));
        JSONArray mentions=body.optJSONArray("mentions");if(mentions!=null&&mentions.length()>30)throw new IllegalArgumentException("QQ提及列表超过30项");
        for(int i=0;mentions!=null&&i<mentions.length();i++){JSONObject mention=mentions.optJSONObject(i);if(mention==null)continue;String id=mention.optString("member_openid",mention.optString("user_openid",mention.optString("id")));
            if(!id.isEmpty()&&!id.matches("[A-Za-z0-9_.:@-]{1,180}"))throw new IllegalArgumentException("QQ提及身份无效");
            if(!inline.contains(id))part(parts,"mention","qq",message,"mentions",i).put("mentionId",id).put("scope",mention.optString("scope","single")).put("positionKnown",false).put("isBotTarget",mention.optBoolean("is_you"));
            else for(int j=0;j<parts.length();j++){JSONObject existing=parts.getJSONObject(j);if("mention".equals(existing.optString("kind"))&&id.equals(existing.optString("mentionId")))existing.put("scope",mention.optString("scope","single")).put("isBotTarget",mention.optBoolean("is_you"));}
        }
        JSONArray attachments=body.optJSONArray("attachments");if(attachments!=null){JSONArray safe=attachments(attachments,"attachments");for(int i=0;i<safe.length();i++){JSONObject attachment=safe.getJSONObject(i);part(parts,attachment.getString("mimeType").startsWith("image/")?"image":"unsupported_media","qq",message,"attachments",i).put("media",attachment).put("contentRead",false);}}
        return new JSONObject().put("version",1).put("provider","qq").put("messageId",message).put("messageIndex",own).put("parts",parts).put("ordering","source_field_positions;cross_field_order_unspecified");
    }
    /** Only a platform-marked self mention may be removed from command prefix; other people stay intact. */
    static String qqCommandText(JSONObject body)throws Exception {
        String text=body.optString("content").trim();JSONArray mentions=body.optJSONArray("mentions");
        for(int i=0;mentions!=null&&i<mentions.length()&&i<30;i++){JSONObject mention=mentions.optJSONObject(i);if(mention==null||!mention.optBoolean("is_you"))continue;String id=mention.optString("member_openid",mention.optString("user_openid",mention.optString("id")));if(!id.matches("[A-Za-z0-9_.:@-]{1,180}"))continue;
            for(String tag:new String[]{"<@"+id+">","<@!"+id+">"})if(text.startsWith(tag))text=text.substring(tag.length()).trim();
        }return text;
    }
    static JSONArray attachments(JSONArray raw,String field)throws Exception {
        if(raw.length()>8)throw new IllegalArgumentException("QQ群附件最多8项");JSONArray safe=new JSONArray();
        for(int i=0;i<raw.length();i++){JSONObject item=raw.getJSONObject(i);String url=ChannelCore.text(item,"url",4096,false),mime=ChannelCore.text(item,"content_type",120,false);Object size=item.opt("size");boolean sizeKnown=size!=null&&size!=JSONObject.NULL;long bytes=sizeKnown?item.optLong("size",-1):0;
            if(sizeKnown&&(!(size instanceof Number)||((Number)size).doubleValue()!=bytes||bytes<0||bytes>20*1024*1024L))throw new IllegalArgumentException("QQ群附件大小无效");
            JSONObject selected=new JSONObject().put("url",url).put("mimeType",mime.toLowerCase(java.util.Locale.ROOT)).put("bytes",bytes).put("declaredSizeKnown",sizeKnown).put("sourceField",field).put("sourceIndex",i);
            if(item.opt("asr_refer_text") instanceof String)selected.put("platformAsrText",bounded(item.getString("asr_refer_text"),4000)).put("asrSource","qq_platform_not_local_transcription");
            if(item.opt("voice_wav_url") instanceof String)selected.put("voiceWavUrl",bounded(item.getString("voice_wav_url"),4096));safe.put(selected);
        }return safe;
    }
    /** Select actual image sources, including native quote payloads; URLs alone never claim visual understanding. */
    public static JSONArray qqImages(JSONObject messageParts)throws Exception {
        JSONArray result=new JSONArray(),parts=messageParts.getJSONArray("parts");Set<String> seen=new HashSet<>();
        for(int i=0;i<parts.length();i++){JSONObject part=parts.getJSONObject(i);if(part.optJSONObject("media")!=null)addImage(result,seen,part.getJSONObject("media"),i);
            JSONArray quoted=part.optJSONArray("attachments");for(int j=0;quoted!=null&&j<quoted.length();j++)addImage(result,seen,quoted.getJSONObject(j),i);}
        if(result.length()>8)throw new IllegalArgumentException("当前及引用图片合计最多8项");return result;
    }
    private static void addImage(JSONArray result,Set<String> seen,JSONObject media,int part)throws Exception {
        if(!media.optString("mimeType").matches("image/(png|jpeg|webp)"))return;String key=media.getString("url")+"\u0000"+media.getLong("bytes");
        if(seen.add(key))result.put(new JSONObject(media.toString()).put("partIndex",part));
    }
    public static JSONObject weixin(JSONObject message,String canonicalId)throws Exception {
        JSONArray raw=message.optJSONArray("item_list");if(raw==null||raw.length()>30)throw new IllegalArgumentException("微信消息项无效");JSONArray parts=new JSONArray();
        for(int i=0;i<raw.length();i++){JSONObject item=raw.optJSONObject(i);if(item==null){part(parts,"unsupported","weixin",canonicalId,"item_list",i).put("wireType","not_object").put("reason","非对象item，未解释为文字或指令");continue;}
            JSONObject reference=item.optJSONObject("ref_msg");if(reference!=null){
                JSONObject original=reference.optJSONObject("message_item");String serverId=index(reference,"svr_id"),embeddedId=original==null?"":index(original,"msg_id");
                JSONObject quote=part(parts,"quote","weixin",canonicalId,"item_list/"+i+"/ref_msg",i).put("quoteRef",serverId.isEmpty()?embeddedId:serverId).put("referenceIdKind",serverId.isEmpty()?"embedded_item_id":"server_message_id").put("availability","unavailable")
                        .put("resolveFromOriginalInbox",!serverId.isEmpty()||!embeddedId.isEmpty()).put("reason","original_text_unavailable");
                if(reference.opt("title") instanceof String)quote.put("preview",bounded(reference.getString("title"),512)).put("previewIsOriginalText",false);
                retainPartialSelection(reference,quote);
                if(original!=null&&original.optJSONObject("text_item")!=null&&original.getJSONObject("text_item").opt("text") instanceof String&&!original.getJSONObject("text_item").getString("text").isEmpty())quotedText(quote,original.getJSONObject("text_item").getString("text"),"native_payload");
            }
            if(number(item.opt("type"),1)&&item.optJSONObject("text_item")!=null&&item.getJSONObject("text_item").opt("text") instanceof String){
                JSONObject textPart=part(parts,"text","weixin",canonicalId,"item_list",i).put("text",bounded(item.getJSONObject("text_item").getString("text"),4000));String nativeId=index(item,"msg_id");if(!nativeId.isEmpty())textPart.put("nativeItemId",nativeId);
            }
            else if(number(item.opt("type"),2))part(parts,"image","weixin",canonicalId,"item_list",i).put("contentRead",false).put("materializationState","not_downloaded").put("mediaKind","image");
            else {String media=number(item.opt("type"),3)?"voice":number(item.opt("type"),4)?"file":number(item.opt("type"),5)?"video":"unknown";
                JSONObject unknown=part(parts,"unsupported","weixin",canonicalId,"item_list",i).put("wireType",item.opt("type")).put("mediaKind",media).put("reason","此端未下载/解密该项，不能声称已读取媒体").put("contentRead",false).put("unsupportedRaw",redacted(item,0));
                if(media.equals("voice")&&item.optJSONObject("voice_item")!=null&&item.getJSONObject("voice_item").opt("text") instanceof String)unknown.put("platformAsrText",bounded(item.getJSONObject("voice_item").getString("text"),4000)).put("asrSource","weixin_platform_not_local_transcription");
            }
        }
        JSONObject result=new JSONObject().put("version",1).put("provider","weixin").put("messageId",canonicalId).put("parts",parts).put("ordering","item_list_order");if(result.toString().length()>65536)throw new IllegalArgumentException("微信混合消息超出有界保留范围");return result;
    }
    private static void retainPartialSelection(JSONObject reference,JSONObject quote)throws Exception {
        if(!reference.has("partial_text"))return;JSONObject raw=reference.optJSONObject("partial_text"),safe=new JSONObject();boolean invalid=raw==null;
        for(String key:new String[]{"start","end"}){Object value=raw==null?null:raw.opt(key);if(!(value instanceof String)||((String)value).length()>512)invalid=true;else safe.put(key,value);}
        for(String key:new String[]{"startindex","endindex"}){Object value=raw==null?null:raw.opt(key);if(!(value instanceof Number))invalid=true;else safe.put(key,value);}
        Object hash=raw==null?null:raw.opt("quotemd5");if(hash!=null&&hash!=JSONObject.NULL){if(!(hash instanceof String)||((String)hash).length()>32)invalid=true;else safe.put("quotemd5",hash);}
        quote.put("partialText",safe).put("partialInvalid",invalid);
    }
    /** Package-native projection only; the caller must obtain cached text from the exact inbox. */
    static void quotedText(JSONObject quote,String original,String origin)throws Exception {
        JSONObject selected=WeixinQuotedText.select(original,quote.optBoolean("partialInvalid")?new JSONObject():quote.optJSONObject("partialText"));quote.put("resolveFromOriginalInbox",false);
        if(selected.getBoolean("available")){quote.put("text",selected.getString("text")).put("availability",origin).put("selection",selected.getString("selection")).put("integrity",selected.getString("integrity"));if(selected.has("indexInterpretation"))quote.put("indexInterpretation",selected.getString("indexInterpretation"));quote.remove("reason");}
        else {quote.remove("text");quote.put("availability","unavailable").put("reason",selected.getString("reason"));}
    }
    public static boolean hasUnsupported(JSONObject structured)throws Exception {
        JSONArray parts=structured.optJSONArray("parts");for(int i=0;parts!=null&&i<parts.length();i++)if(parts.getJSONObject(i).optString("kind").matches("unsupported|unsupported_media")||structured.optString("provider").equals("weixin")&&parts.getJSONObject(i).optString("kind").equals("image"))return true;return false;
    }
    /** Current actual image item positions only; quoted/forwarded payloads are not promoted. */
    static JSONArray weixinImagePositions(JSONObject structured)throws Exception {
        JSONArray result=new JSONArray();if(structured==null||!structured.optString("provider").equals("weixin"))return result;JSONArray parts=structured.optJSONArray("parts");
        for(int i=0;parts!=null&&i<parts.length();i++){JSONObject part=parts.getJSONObject(i),source=part.optJSONObject("source");if(part.optString("kind").equals("image")&&source!=null&&source.optString("provider").equals("weixin")&&source.optString("field").equals("item_list"))result.put(source.getInt("position"));}return result;
    }
    static boolean hasUnprocessed(JSONObject structured,int suppliedImages)throws Exception {
        if(structured==null)return false;JSONArray parts=structured.optJSONArray("parts");for(int i=0;parts!=null&&i<parts.length();i++)if(parts.getJSONObject(i).optString("kind").matches("unsupported|unsupported_media"))return true;return weixinImagePositions(structured).length()>suppliedImages;
    }
    public static String currentText(JSONObject structured)throws Exception {
        StringBuilder text=new StringBuilder();JSONArray parts=structured.getJSONArray("parts");
        for(int i=0;i<parts.length();i++){JSONObject part=parts.getJSONObject(i);if(!part.optString("kind").equals("text"))continue;if(text.length()>0)text.append('\n');text.append(part.getString("text"));if(text.length()>4000)throw new IllegalArgumentException("消息文字超过4000字符");}return text.toString();
    }
    /** User/model-visible explanation, while quote and unknown data remain separate from the current request. */
    public static String context(JSONObject structured)throws Exception {
        StringBuilder text=new StringBuilder();JSONArray parts=structured.optJSONArray("parts");
        for(int i=0;parts!=null&&i<parts.length();i++){JSONObject part=parts.getJSONObject(i);String kind=part.optString("kind");
            if(kind.equals("quote")){
                String availability=part.optString("availability"),label=availability.equals("native_payload")?"消息内原文":availability.equals("original_inbox")?"本机已收原文（同账号/同会话）":"原文不可用，不补造";
                text.append("\n[原平台引用 ").append(part.optString("quoteRef")).append(" · ").append(label);if(part.optString("selection").equals("selected_substring"))text.append(" · 仅选中片段/非整条");text.append("]\n").append(part.optString("text"));
                if(availability.equals("unavailable")&&!part.optString("preview").isEmpty())text.append("\n[平台摘要而非原文] ").append(part.getString("preview"));
            }
            else if(kind.equals("unsupported")||kind.equals("unsupported_media"))text.append("\n[消息第").append(i+1).append("段未处理：").append(part.optString("mediaKind",kind)).append("；未读取媒体内容，不得假称已看图/听音]");
        }
        if(text.length()>6000)throw new IllegalArgumentException("消息引用/不支持部分超过上下文上限");return text.toString();
    }
    public static JSONObject authenticatedCopy(JSONObject supplied,String provider,String message)throws Exception {
        if(supplied==null)return new JSONObject().put("version",1).put("provider",provider).put("messageId",message).put("parts",new JSONArray());
        if(!provider.equals(supplied.optString("provider"))||!message.equals(supplied.optString("messageId"))||supplied.optJSONArray("parts")==null||supplied.getJSONArray("parts").length()>MAX_PARTS||supplied.toString().length()>65536)throw new SecurityException("消息parts与认证平台来源不一致");return new JSONObject(supplied.toString());
    }
    private static JSONObject part(JSONArray parts,String kind,String provider,String message,String field,int position)throws Exception {
        if(parts.length()>=MAX_PARTS)throw new IllegalArgumentException("消息parts超过64项");JSONObject value=new JSONObject().put("index",parts.length()).put("kind",kind).put("source",source(provider,message,field,position));parts.put(value);return value;
    }
    private static JSONObject source(String provider,String message,String field,int position)throws Exception{return new JSONObject().put("provider",provider).put("messageId",message).put("field",field).put("position",position);}
    private static String index(JSONObject object,String key){String value=object.optString(key);return validIndex(value)?value:"";}
    private static boolean validIndex(String value){return value!=null&&value.matches("[A-Za-z0-9_.:@-]{1,180}");}
    private static boolean number(Object value,int expected){return value instanceof Number&&((Number)value).doubleValue()==expected;}
    private static String bounded(String value,int limit){int end=Math.min(value.length(),limit);if(end<value.length()&&end>0&&Character.isHighSurrogate(value.charAt(end-1)))end--;return value.substring(0,end);}
    private static JSONObject redacted(JSONObject object,int depth)throws Exception {
        JSONObject result=new JSONObject();if(depth>4)return result.put("omitted","depth_limit");int count=0;
        for(java.util.Iterator<String> keys=object.keys();keys.hasNext()&&count++<20;){String key=keys.next();Object value=object.opt(key);
            if(key.matches("(?i).*(token|aes.?key|encrypt.*param|secret|password|authorization|full_url).*"))value="[未保留凭据或下载授权]";
            else if(value instanceof JSONObject)value=redacted((JSONObject)value,depth+1);
            else if(value instanceof JSONArray)value="[array:"+((JSONArray)value).length()+"]";
            else if(value instanceof String)value=bounded((String)value,512);
            result.put(key,value);
        }return result;
    }
}
