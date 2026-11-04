package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.runtime.usage.NativeInferenceWork;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Actual production login/poll/context-vault/core/model/outbox wire against a controlled iLink
 * fixture, not a real QR login or WeChat account. No group support or external sends claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class WeixinQuoteNativeJourneyTest {
    @Before public void key(){java.security.Security.addProvider(new java.security.Provider("WeixinQuoteKeyStore","1","Explicit fixture"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void release(){java.security.Security.removeProvider("WeixinQuoteKeyStore");}
    private static JSONObject message(String id,String text,String context,JSONObject ref)throws Exception {
        JSONObject item=new JSONObject().put("type",1).put("text_item",new JSONObject().put("text",text));if(ref!=null)item.put("ref_msg",ref);
        return new JSONObject().put("message_id",id).put("from_user_id","original-human@im.wechat").put("message_type",1).put("message_state",2).put("create_time_ms",System.currentTimeMillis()).put("context_token",context).put("item_list",new JSONArray().put(item));
    }
    private static String pollBody(JSONObject message,String cursor,boolean numericId)throws Exception {String body=new JSONObject().put("ret",0).put("msgs",new JSONArray().put(message)).put("get_updates_buf",cursor).toString();return numericId?body.replace("\"message_id\":\"18446744073709551615\"","\"message_id\":18446744073709551615"):body;}
    private static void sent(ChannelsModule module,int count)throws Exception {long deadline=System.nanoTime()+5_000_000_000L;while(true){JSONArray rows=module.snapshot().getJSONArray("outbox");int n=0;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).optString("status").equals("sent"))n++;if(n==count)return;if(System.nanoTime()>deadline)fail("original iLink outbox did not settle: "+rows);Thread.sleep(10);}}
    private static JSONObject event(ChannelsModule module,String message)throws Exception {JSONArray rows=module.snapshot().getJSONArray("inbox");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("messageId").equals(message))return rows.getJSONObject(i);throw new AssertionError("missing original message "+message);}
    @Test public void originalNumericIdTextThenColdModuleIdOnlyPartialQuoteUsesExactNewContextAndNeverRepeatsDuplicate()throws Exception {
        Context android=RuntimeEnvironment.getApplication();android.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(android);FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),ignored->{});
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicReference<String> updates=new AtomicReference<>();AtomicInteger modelCalls=new AtomicInteger(),polls=new AtomicInteger();CopyOnWriteArrayList<JSONObject> models=new CopyOnWriteArrayList<>(),delivered=new CopyOnWriteArrayList<>(),pollRequests=new CopyOnWriteArrayList<>();
        String token="ilink-native-fixture-token-not-real";
        server.createContext("/",exchange->{try{String path=exchange.getRequestURI().getPath();byte[] incoming=exchange.getRequestBody().readAllBytes();JSONObject body=incoming.length==0?new JSONObject():new JSONObject(new String(incoming,StandardCharsets.UTF_8));String response;
            if(path.equals("/ilink/bot/get_bot_qrcode")){assertNull(exchange.getRequestHeaders().getFirst("Authorization"));response=new JSONObject().put("qrcode","fixture-qr-id").put("qrcode_img_content","FIXTURE-NOT-A-REAL-LOGIN").toString();}
            else if(path.equals("/ilink/bot/get_qrcode_status")){assertNull(exchange.getRequestHeaders().getFirst("Authorization"));response=new JSONObject().put("status","confirmed").put("bot_token",token).put("ilink_bot_id","fixture-original-bot@im.bot").put("baseurl",WeixinClient.DEFAULT_BASE).toString();}
            else if(path.equals("/ilink/bot/getupdates")){assertEquals("Bearer "+token,exchange.getRequestHeaders().getFirst("Authorization"));polls.incrementAndGet();pollRequests.add(body);response=updates.get();assertNotNull(response);}
            else if(path.equals("/ilink/bot/sendmessage")){assertEquals("Bearer "+token,exchange.getRequestHeaders().getFirst("Authorization"));delivered.add(body.getJSONObject("msg"));response="{\"ret\":0}";}
            else if(path.equals("/v1/chat/completions")){models.add(body);int n=modelCalls.incrementAndGet();assertFalse(body.toString().contains(token));assertFalse(body.toString().contains("ctx-source"));assertFalse(body.toString().contains("ctx-exact-quote"));assertFalse(body.has("tools"));response=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content",n==1?"已回答本次原始文字":"已按原引用的选中片段解释")))).toString();}
            else throw new AssertionError("unexpected fixture route "+path);
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();ChannelsModule module=null;
        try{
            context.settings.save(new JSONObject().put("baseUrl",base+"/v1").put("provider","openai_chat").put("model","fixture-api-model").put("maxOutputTokens",256).put("maxSteps",8));
            module=new ChannelsModule(context,new ChannelHttp(base),new QqGatewayClient(true),new WeixinClient(base));module.setInferenceFactory((original,source)->NativeInferenceWork.open(tasks,TaskStore.NativeInferenceKind.CHANNEL_CHAT,source,original));String login=module.handle("weixin.login.start",new JSONObject().put("name","Original iLink fixture")).getString("loginId"),profile=module.handle("weixin.login.poll",new JSONObject().put("loginId",login)).getJSONObject("profile").getString("id");
            updates.set(pollBody(message("1","Native binding source only","ctx-bind",null),"cursor-bind",false));module.handle("weixin.poll",new JSONObject().put("id",profile));JSONObject binding=event(module,"wx-1");module.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",binding.getString("id")).put("confirmed",true));
            module.handle("profile.save",new JSONObject().put("id",profile).put("name","Native iLink owner").put("provider","weixin").put("appId","fixture-original-bot@im.bot").put("apiBase",WeixinClient.DEFAULT_BASE).put("enabled",true).put("autoReply",true));
            updates.set(pollBody(message("18446744073709551615","前[目标🙂]后，原文未声称来自其他会话","ctx-source",null),"cursor-source",true));module.handle("weixin.poll",new JSONObject().put("id",profile));sent(module,1);JSONObject original=event(module,"wx-18446744073709551615");assertEquals("ctx-source",delivered.get(0).getString("context_token"));assertEquals(1,modelCalls.get());
            JSONObject firstUsage=original.getJSONObject("latestInferenceReference");assertUsage(tasks,firstUsage,original.getString("id"));
            module.close();module=new ChannelsModule(context,new ChannelHttp(base),new QqGatewayClient(true),new WeixinClient(base));module.setInferenceFactory((control,source)->NativeInferenceWork.open(tasks,TaskStore.NativeInferenceKind.CHANNEL_CHAT,source,control));StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("MD5").digest("[目标🙂]".getBytes(StandardCharsets.UTF_8)))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            JSONObject reference=new JSONObject().put("svr_id","18446744073709551615").put("partial_text",new JSONObject().put("start","[").put("startindex",0).put("end","]").put("endindex",0).put("quotemd5",hash.toString()));JSONObject followup=message("2","请仅按选中引用解释","ctx-exact-quote",reference);updates.set(pollBody(followup,"cursor-quote",false));module.handle("weixin.poll",new JSONObject().put("id",profile));sent(module,2);
            JSONObject current=event(module,"wx-2"),quote=current.getJSONObject("messageParts").getJSONArray("parts").getJSONObject(0);assertEquals("original_inbox",quote.getString("availability"));assertEquals("[目标🙂]",quote.getString("text"));assertEquals("md5_matched",quote.getString("integrity"));assertEquals(original.getString("id"),quote.getJSONObject("originalSource").getString("eventId"));
            assertEquals(2,modelCalls.get());assertEquals(2,delivered.size());JSONObject reply=delivered.get(1);assertEquals("ctx-exact-quote",reply.getString("context_token"));assertEquals("original-human@im.wechat",reply.getString("to_user_id"));assertEquals("已按原引用的选中片段解释",reply.getJSONArray("item_list").getJSONObject(0).getJSONObject("text_item").getString("text"));assertEquals("cursor-source",pollRequests.get(2).getString("get_updates_buf"));
            JSONArray wireMessages=models.get(1).getJSONArray("messages");StringBuilder decoded=new StringBuilder();for(int i=0;i<wireMessages.length();i++)decoded.append(wireMessages.getJSONObject(i).getString("content")).append("\n");String request=decoded.toString();assertTrue(request,request.contains("同账号/同会话"));assertTrue(request,request.contains("仅选中片段"));assertTrue(request,request.contains("[目标🙂]"));assertFalse(current.toString().contains(token));assertEquals(reply.getString("client_id"),module.snapshot().getJSONArray("outbox").getJSONObject(1).getString("id"));
            JSONObject secondUsage=current.getJSONObject("latestInferenceReference");assertUsage(tasks,secondUsage,current.getString("id"));assertNotEquals(firstUsage.getString("taskId"),secondUsage.getString("taskId"));
            module.handle("weixin.poll",new JSONObject().put("id",profile));assertEquals(4,polls.get());assertEquals(2,modelCalls.get());assertEquals(2,delivered.size());assertEquals(2,tasks.nativeInferenceUsagePage("",40).getJSONArray("tasks").length());assertFalse(module.snapshot().getJSONObject("weixin").getBoolean("groupMessages"));
        }finally{if(module!=null)module.close();server.stop(0);var db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(tasks)).close();}
    }
    private static void assertUsage(TaskStore tasks,JSONObject reference,String event)throws Exception {
        String task=reference.getString("taskId");assertTrue(reference.getBoolean("accountingOnly"));assertFalse(reference.getBoolean("executionAuthorized"));
        JSONObject saved=tasks.get(task,false);assertEquals("completed",saved.getString("status"));assertEquals(event,saved.getJSONObject("origin").getString("sourceId"));
        JSONObject usage=tasks.inferenceUsage(task);assertEquals(1,usage.getInt("attempts"));JSONObject receipt=usage.getJSONArray("receipts").getJSONObject(0);
        assertEquals("CHANNEL_CHAT",receipt.getJSONObject("context").getString("purpose"));assertEquals("COMPLETED",receipt.getString("outcome"));
        // This controlled provider omitted usage: retain unknown, never invent zero tokens.
        assertTrue(receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("TOTAL").isNull("value"));
        assertThrows(SecurityException.class,()->tasks.startAttempt(task,"capabilities",8));
    }
}
