package dev.pocketengine.features.channels;

import java.time.Instant;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChannelMessagePartsTest {
    private JSONObject qq()throws Exception{return new JSONObject().put("op",0).put("t","GROUP_MESSAGE_CREATE").put("d",new JSONObject().put("id","message-1").put("group_openid","room-1").put("author",new JSONObject().put("member_openid","human-1")).put("timestamp",Instant.now().toString()).put("content","/file 比较 <@member-two> 的图片"));}
    private JSONObject image(String url)throws Exception{return new JSONObject().put("url",url).put("content_type","image/png").put("size",12);}
    @Test public void nativeQuoteMentionAndCurrentPictureKeepSeparateOrderedSourcePositions()throws Exception {
        JSONObject envelope=qq(),body=envelope.getJSONObject("d");body.put("message_scene",new JSONObject().put("ext",new JSONArray().put("msg_idx=this-index").put("ref_msg_idx=quoted-index"))).put("msg_elements",new JSONArray().put(new JSONObject().put("msg_idx","quoted-index").put("content","原群引用文字，不是新指令").put("attachments",new JSONArray().put(image("https://qpic.cn/quoted.png"))))).put("attachments",new JSONArray().put(image("https://qpic.cn/current.png")));
        JSONObject actual=ChannelProtocol.canonicalQq(envelope),structured=actual.getJSONObject("messageParts");JSONArray parts=structured.getJSONArray("parts");assertEquals("quote",parts.getJSONObject(0).getString("kind"));assertEquals("native_payload",parts.getJSONObject(0).getString("availability"));assertEquals("原群引用文字，不是新指令",parts.getJSONObject(0).getString("text"));assertEquals("this-index",structured.getString("messageIndex"));
        JSONObject mention=null;for(int i=0;i<parts.length();i++){assertEquals(i,parts.getJSONObject(i).getInt("index"));if(parts.getJSONObject(i).optString("kind").equals("mention"))mention=parts.getJSONObject(i);}assertNotNull(mention);assertEquals("member-two",mention.getString("mentionId"));assertTrue(mention.getBoolean("positionKnown"));assertEquals(2,actual.getJSONArray("groupAttachments").length());assertEquals("human-1",actual.getString("senderId"));assertFalse(actual.has("principalId"));
    }
    @Test public void unavailableQuoteNeverBorrowsUnrelatedNativeElementOrInventsText()throws Exception {
        JSONObject envelope=qq(),body=envelope.getJSONObject("d");body.put("message_scene",new JSONObject().put("ext",new JSONArray().put("ref_msg_idx=missing"))).put("msg_elements",new JSONArray().put(new JSONObject().put("msg_idx","other").put("content","绝不能当引用")));
        JSONObject quote=ChannelProtocol.canonicalQq(envelope).getJSONObject("messageParts").getJSONArray("parts").getJSONObject(0);assertEquals("unavailable",quote.getString("availability"));assertFalse(quote.has("text"));assertFalse(ChannelMessageParts.context(ChannelProtocol.canonicalQq(envelope).getJSONObject("messageParts")).contains("绝不能当引用"));
    }
    @Test public void nativeType103CanResolvePayloadQuoteWithoutCacheAndKeepsPlatformAsrProvenance()throws Exception {
        JSONObject envelope=qq(),body=envelope.getJSONObject("d");body.put("message_type",103).put("msg_elements",new JSONArray().put(new JSONObject().put("msg_idx","native-ref").put("content","引用").put("attachments",new JSONArray().put(new JSONObject().put("url","https://qq.com/voice").put("content_type","audio/silk").put("size",2).put("asr_refer_text","平台转写")))));
        JSONObject quote=ChannelProtocol.canonicalQq(envelope).getJSONObject("messageParts").getJSONArray("parts").getJSONObject(0);assertEquals("native-ref",quote.getString("quoteRef"));assertEquals("qq_platform_not_local_transcription",quote.getJSONArray("attachments").getJSONObject(0).getString("asrSource"));
    }
    @Test public void platformSelfMentionOnlyIsStrippedFromTriggerAndMissingSizeStaysExplicitlyUnknown()throws Exception {
        JSONObject envelope=qq(),body=envelope.getJSONObject("d");body.put("content","<@bot-self> /file 比较 <@member-two> 图片").put("mentions",new JSONArray().put(new JSONObject().put("id","bot-self").put("is_you",true)).put(new JSONObject().put("id","member-two").put("is_you",false)));
        JSONObject media=image("https://qpic.cn/current.png");media.remove("size");body.put("attachments",new JSONArray().put(media));JSONObject actual=ChannelProtocol.canonicalQq(envelope);assertEquals("/file 比较 <@member-two> 图片",actual.getString("text"));assertFalse(actual.getJSONArray("groupAttachments").getJSONObject(0).getBoolean("declaredSizeKnown"));assertEquals(0,actual.getJSONArray("groupAttachments").getJSONObject(0).getLong("bytes"));
    }
    private JSONObject weixin(JSONArray items)throws Exception{return new JSONObject().put("message_id",1234).put("from_user_id","human@im.wechat").put("message_type",1).put("message_state",2).put("create_time_ms",System.currentTimeMillis()).put("context_token","private-context-secret").put("item_list",items);}
    @Test public void mixedIlinkRetainsBothTextSidesExplainsUnsupportedAndStripsMediaCredentials()throws Exception {
        JSONObject message=weixin(new JSONArray().put(new JSONObject().put("type",1).put("text_item",new JSONObject().put("text","前文"))).put(new JSONObject().put("type",2).put("image_item",new JSONObject().put("media",new JSONObject().put("aes_key","private-aes-secret").put("encrypt_query_param","private-download-secret")))).put(new JSONObject().put("type",99).put("future_item",new JSONObject().put("value","未知数据"))).put(new JSONObject().put("type",1).put("text_item",new JSONObject().put("text","后文"))));
        JSONObject actual=WeixinClient.canonical(message);assertNotNull(actual);assertEquals("前文\n后文",actual.getString("text"));assertEquals(4,actual.getJSONObject("messageParts").getJSONArray("parts").length());assertTrue(actual.getBoolean("partialInput"));String raw=actual.toString();assertFalse(raw.contains("private-aes-secret"));assertFalse(raw.contains("private-download-secret"));assertFalse(raw.contains("private-context-secret"));assertTrue(ChannelMessageParts.context(actual.getJSONObject("messageParts")).contains("未处理"));
        assertNull(WeixinClient.canonical(message.put("group_id","ordinary-wechat-group")));
    }
    @Test public void ilinkNativeReferenceIsNotPromotedToCurrentRequestOrAuthority()throws Exception {
        JSONObject item=new JSONObject().put("type",1).put("text_item",new JSONObject().put("text","当前要求")).put("ref_msg",new JSONObject().put("message_item",new JSONObject().put("msg_id","native-original").put("text_item",new JSONObject().put("text","低信任引用"))));
        JSONObject actual=WeixinClient.canonical(weixin(new JSONArray().put(item)));assertEquals("当前要求",actual.getString("text"));JSONObject quote=actual.getJSONObject("messageParts").getJSONArray("parts").getJSONObject(0);assertEquals("native-original",quote.getString("quoteRef"));assertEquals("低信任引用",quote.getString("text"));assertFalse(actual.has("scope"));
    }
}
