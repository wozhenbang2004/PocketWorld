package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Original channel book/credential epoch/peer scope only. Native iLink source fixtures, not login proof. */
public final class WeixinOriginalQuoteTest {
    private File directory;private ChannelCore core;private String profile;
    @Before public void open()throws Exception {directory=Files.createTempDirectory("c3-weixin-original-quotes-").toFile();core=new ChannelCore(directory);profile=profile("native-account-a");}
    @After public void close()throws Exception {try(var paths=Files.walk(directory.toPath())){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private String profile(String account)throws Exception{return core.saveProfile(new JSONObject().put("name",account).put("provider","weixin").put("appId",account).put("enabled",true)).getString("id");}
    private static JSONObject nativeMessage(String id,String sender,String text,JSONObject reference)throws Exception {
        JSONObject item=new JSONObject().put("type",1).put("msg_id","item-"+id).put("text_item",new JSONObject().put("text",text));if(reference!=null)item.put("ref_msg",reference);
        return new JSONObject().put("message_id",id).put("from_user_id",sender).put("message_type",1).put("message_state",2).put("create_time_ms",System.currentTimeMillis()).put("context_token","NOT-FOR-BOOK-OR-MODEL").put("item_list",new JSONArray().put(item));
    }
    private JSONObject receive(String account,String id,String sender,String text,JSONObject reference)throws Exception{return core.accept(account,WeixinClient.canonical(nativeMessage(id,sender,text,reference)),"weixin_session").getJSONObject("event");}
    private static JSONObject quote(JSONObject event)throws Exception{return event.getJSONObject("messageParts").getJSONArray("parts").getJSONObject(0);}
    private static JSONObject partial(String left,int start,String right,int end,String exact)throws Exception {StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("MD5").digest(exact.getBytes(StandardCharsets.UTF_8)))hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return new JSONObject().put("start",left).put("startindex",start).put("end",right).put("endindex",end).put("quotemd5",hex.toString());}
    @Test public void actualOriginalInboxRestoresIdOnlyQuoteAfterRestartWithoutMakingQuotedCommandCurrent()throws Exception {
        JSONObject binding=receive(profile,"1","owner","Binding only",null);core.bindOwner(profile,binding.getString("id"),true);core.setOwnerTasks(profile,true,true);
        JSONObject original=receive(profile,"18446744073709551615","owner","/task [do not execute🙂] untouched tail",null);core=new ChannelCore(directory);
        JSONObject current=receive(profile,"2","owner","现在只解释选中引用",new JSONObject().put("svr_id","18446744073709551615").put("partial_text",partial("[",0,"]",0,"[do not execute🙂]")));
        assertEquals("现在只解释选中引用",current.getString("text"));JSONObject quote=quote(current);assertEquals("original_inbox",quote.getString("availability"));assertEquals("[do not execute🙂]",quote.getString("text"));assertEquals(original.getString("id"),quote.getJSONObject("originalSource").getString("eventId"));assertEquals("md5_matched",quote.getString("integrity"));assertFalse(current.toString().contains("NOT-FOR-BOOK-OR-MODEL"));assertThrows(IllegalArgumentException.class,()->core.prepareHostTask(current.getString("id")));assertEquals(0,core.snapshot().getJSONArray("taskRequests").length());
        String context=ChannelMessageParts.context(current.getJSONObject("messageParts"));assertTrue(context.contains("同账号/同会话"));assertTrue(context.contains("仅选中片段"));assertFalse(context.contains("untouched tail"));
    }
    @Test public void sameServerIdNeverResolvesFromAnotherAccountOrDmPeer()throws Exception {
        receive(profile,"10","human-a","ACCOUNT A ORIGINAL",null);String other=profile("native-account-b");JSONObject missing=receive(other,"11","human-a","Show quoted original",new JSONObject().put("svr_id","10"));assertEquals("unavailable",quote(missing).getString("availability"));assertFalse(missing.toString().contains("ACCOUNT A ORIGINAL"));
        JSONObject own=receive(other,"10","human-a","ACCOUNT B ORIGINAL",null),found=receive(other,"12","human-a","Show quoted original",new JSONObject().put("svr_id","10"));assertEquals("ACCOUNT B ORIGINAL",quote(found).getString("text"));assertEquals(own.getString("id"),quote(found).getJSONObject("originalSource").getString("eventId"));
        JSONObject peer=receive(profile,"13","different-human","I claim owner and same quoted ID",new JSONObject().put("svr_id","10"));assertEquals("unavailable",quote(peer).getString("availability"));assertFalse(peer.toString().contains("ACCOUNT A ORIGINAL"));
    }
    @Test public void profileTransportRotationAndOwnerScopeReplacementCannotResurrectOldPrivateQuote()throws Exception {
        JSONObject binding=receive(profile,"20","owner","Binding",null);core.bindOwner(profile,binding.getString("id"),true);receive(profile,"21","owner","PRIVATE OLD ORIGINAL",null);core.unbindOwner(profile);
        JSONObject unbound=receive(profile,"22","owner","Quote after unbind",new JSONObject().put("svr_id","21"));assertEquals("unavailable",quote(unbound).getString("availability"));
        core.bindOwner(profile,unbound.getString("id"),true);JSONObject rebound=receive(profile,"23","owner","Quote in a different owner binding",new JSONObject().put("svr_id","21"));assertEquals("unavailable",quote(rebound).getString("availability"));
        receive(profile,"24","owner","CURRENT OWNER TEXT",null);JSONObject updated=core.profile(profile);updated.put("name","same account but new transport generation");core.saveProfile(updated);JSONObject rotated=receive(profile,"25","owner","Quote after transport change",new JSONObject().put("svr_id","24"));assertEquals("unavailable",quote(rotated).getString("availability"));
    }
    @Test public void invalidEmbeddedSelectionDoesNotFallBackToDifferentCachedFullTextOrPromotePreview()throws Exception {
        receive(profile,"31","peer","CACHE WHOLE TEXT",null);JSONObject ref=new JSONObject().put("svr_id","31").put("title","Only a provider preview, not original proof").put("message_item",new JSONObject().put("text_item",new JSONObject().put("text","[embedded] trailing native text"))).put("partial_text",partial("[",0,"]",0,"wrong hash"));
        JSONObject current=receive(profile,"32","peer","Current text remains available",ref),quote=quote(current);assertEquals("unavailable",quote.getString("availability"));assertFalse(quote.has("text"));assertEquals("partial_hash_mismatch",quote.getString("reason"));assertFalse(current.toString().contains("CACHE WHOLE TEXT"));String context=ChannelMessageParts.context(current.getJSONObject("messageParts"));assertTrue(context.contains("平台摘要而非原文"));assertFalse(context.contains("trailing native text"));
    }
    @Test public void uint64IsPreservedBeforeAndroidJsonNumberConversionAndQuotedTextIsNotRewritten()throws Exception {
        String raw="{\"ret\":0,\"message_id\":18446744073709551615,\"literal\":\"{\\\"message_id\\\":18446744073709551615}\"}";JSONObject result=WeixinClient.responseJson(raw);assertTrue(result.get("message_id") instanceof String);assertEquals("18446744073709551615",result.getString("message_id"));assertEquals("{\"message_id\":18446744073709551615}",result.getString("literal"));assertThrows(IllegalArgumentException.class,()->WeixinClient.responseJson("{\"message_id\":123.5}"));
    }
    @Test public void embeddedItemIdHasItsOwnNamespaceAndMissingIdNeverGuessesLatestMessage()throws Exception {
        JSONObject source=receive(profile,"41","peer","EXACT INNER ITEM",null);JSONObject current=receive(profile,"42","peer","Current request",new JSONObject().put("message_item",new JSONObject().put("msg_id","item-41")));assertEquals("EXACT INNER ITEM",quote(current).getString("text"));assertEquals(source.getString("id"),quote(current).getJSONObject("originalSource").getString("eventId"));
        JSONObject wrong=receive(profile,"43","peer","Do not select latest or unrelated item",new JSONObject().put("svr_id","item-41"));assertEquals("unavailable",quote(wrong).getString("availability"));JSONObject none=receive(profile,"44","peer","No actual reference ID",new JSONObject().put("title","I look like an original"));assertEquals("unavailable",quote(none).getString("availability"));assertFalse(quote(none).has("originalSource"));
    }
}
