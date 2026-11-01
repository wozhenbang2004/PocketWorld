package dev.pocketengine.features.channels;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Actual native inbox/Book/model HTTP/outbox with a controlled transport-admission fixture.
 * Not a real QQ account, gateway qualification, external CDN or device assertion. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class QqOwnerImageJourneyTest {
    private static final byte[] PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMwbzr5HwAE+AKC+5SnugAAAABJRU5ErkJggg==");
    @Before public void vault(){java.security.Security.addProvider(new java.security.Provider("QqImageFixtureVault","1","Test-only AndroidKeyStore SPI"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void release(){java.security.Security.removeProvider("QqImageFixtureVault");}
    private static JSONObject wire(String id,String sender,String content,String url)throws Exception {JSONObject data=new JSONObject().put("id",id).put("author",new JSONObject().put("user_openid",sender)).put("content",content).put("timestamp",Instant.now().toString());if(url!=null)data.put("attachments",new JSONArray().put(new JSONObject().put("url",url).put("content_type","image/png").put("size",PNG.length)));return new JSONObject().put("op",0).put("t","C2C_MESSAGE_CREATE").put("s",1).put("d",data);}
    static final class Fixture implements AutoCloseable {
        final Context android;final FeatureContext context;final ChannelsModule module;final ChannelCore core;final HttpServer server;final String base;final AtomicInteger cdn=new AtomicInteger(),modelCalls=new AtomicInteger(),sent=new AtomicInteger();final CopyOnWriteArrayList<JSONObject> requests=new CopyOnWriteArrayList<>();final AtomicReference<Throwable> failure=new AtomicReference<>();volatile byte[] download=PNG;
        Fixture()throws Exception {Context app=RuntimeEnvironment.getApplication();java.io.File files=Files.createTempDirectory(app.getFilesDir().toPath(),"qq-owner-image-").toFile();android=new ContextWrapper(app){public java.io.File getFilesDir(){return files;}public Context getApplicationContext(){return this;}};context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),ignored->{});
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{String path=exchange.getRequestURI().getPath();byte[] out;
                if(path.equals("/image")){cdn.incrementAndGet();String auth=exchange.getRequestHeaders().getFirst("Authorization"),cookie=exchange.getRequestHeaders().getFirst("Cookie");assertTrue(auth==null||auth.isEmpty());assertTrue(cookie==null||cookie.isEmpty());out=download;}
                else {byte[] raw=exchange.getRequestBody().readAllBytes();JSONObject body=new JSONObject(new String(raw,StandardCharsets.UTF_8)),response;
                    if(path.equals("/v1/chat/completions")){modelCalls.incrementAndGet();requests.add(body);assertFalse(body.has("tools"));assertFalse(body.toString().contains("/image"));response=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content","Controlled reply from actual supplied image bytes"))));}
                    else if(path.equals("/app/getAppAccessToken"))response=new JSONObject().put("access_token","fixture-qq-token").put("expires_in",7200);
                    else if(path.equals("/v2/users/owner/messages")){sent.incrementAndGet();assertEquals("QQBot fixture-qq-token",exchange.getRequestHeaders().getFirst("Authorization"));response=new JSONObject().put("id","fixture-platform-receipt");}
                    else throw new AssertionError("Unexpected controlled route "+path);out=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");}
                exchange.sendResponseHeaders(200,out.length);exchange.getResponseBody().write(out);
            }catch(Throwable problem){failure.compareAndSet(null,problem);}finally{exchange.close();}});server.start();base="http://127.0.0.1:"+server.getAddress().getPort();context.settings.save(new JSONObject().put("baseUrl",base+"/v1").put("provider","openai_chat").put("model","qq-native-image-model").put("supportsImageInput",true).put("maxOutputTokens",256));context.secrets.put("channels.bot.secret","fixture-qq-secret-longer-than-32-characters");module=new ChannelsModule(context,new ChannelHttp(base),new QqGatewayClient(true),new WeixinClient(base),new GroupInboundFiles(true));var field=ChannelsModule.class.getDeclaredField("core");field.setAccessible(true);core=(ChannelCore)field.get(module);core.saveProfile(new JSONObject().put("id","bot").put("provider","qq").put("name","Controlled QQ source").put("appId","fixture-app").put("enabled",true).put("ownerImagesEnabled",true));}
        JSONObject receive(String id,String sender,String text,String url)throws Exception {return core.accept("bot",ChannelProtocol.canonicalQq(wire(id,sender,text,url)),"qq_gateway").getJSONObject("event");}
        void bind()throws Exception {JSONObject event=receive("binding","owner","Native owner verification",null);module.handle("owner.bind",new JSONObject().put("profileId","bot").put("verifiedEventId",event.getString("id")).put("confirmed",true));}
        JSONObject await(JSONObject original,String status)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(true){JSONObject actual=core.event(original.getString("id"));if(actual.optString("status").equals(status)){var f=ChannelsModule.class.getDeclaredField("answering");f.setAccessible(true);if(!((java.util.Map<?,?>)f.get(module)).containsKey(original.getString("id")))return actual;}if(System.nanoTime()>until)throw new AssertionError("Original image did not settle: "+actual);Thread.sleep(5);}}
        public void close(){module.close();server.stop(0);if(failure.get()!=null)throw new AssertionError(failure.get());}
    }
    @Test public void actualQqImageBytesReachOriginalBookModelAndOneOriginalC2cReplyWithCurrentSourceReadback()throws Exception {
        try(Fixture f=new Fixture()){f.bind();JSONObject original=f.receive("image-one","owner","",f.base+"/image");f.module.handle("ask",new JSONObject().put("eventId",original.getString("id")));JSONObject result=f.await(original,"answered");assertEquals(1,f.cdn.get());assertEquals(1,f.modelCalls.get());JSONObject reference=result.getJSONArray("ownerImageArtifacts").getJSONObject(0);byte[] frozen=AttachmentRepository.get(f.android).book.readVerifiedSnapshot(reference.getString("artifactId"),reference.getString("sha256"),new RunControl());assertArrayEquals(PNG,frozen);
            JSONObject request=f.requests.get(0);JSONArray messages=request.getJSONArray("messages"),content=messages.getJSONObject(messages.length()-1).getJSONArray("content");String image="";for(int i=0;i<content.length();i++)if(content.getJSONObject(i).optString("type").equals("image_url"))image=content.getJSONObject(i).getJSONObject("image_url").getString("url");assertArrayEquals(PNG,Base64.getDecoder().decode(image.substring(image.indexOf(',')+1)));
            ChannelsModule.PrivateInputSource source=f.module.capturePrivateRemoteInput(reference.getString("artifactId"),reference.getString("sha256"));assertEquals("qq",source.view().getString("provider"));assertEquals("qq_c2c",source.view().getString("conversationKind"));source.checkCurrent();JSONObject outbox=f.module.snapshot().getJSONArray("outbox").getJSONObject(0);f.module.handle("outbox.send",new JSONObject().put("id",outbox.getString("id")));assertEquals(1,f.sent.get());assertEquals("sent",f.module.snapshot().getJSONArray("outbox").getJSONObject(0).getString("status"));
            f.download=new byte[]{9,8,7};f.module.handle("ask",new JSONObject().put("eventId",original.getString("id")));f.await(original,"answered");assertEquals(1,f.cdn.get());assertEquals(2,f.modelCalls.get());f.module.handle("owner.unbind",new JSONObject().put("profileId","bot"));assertThrows(SecurityException.class,source::checkCurrent);assertThrows(SecurityException.class,()->f.module.capturePrivateRemoteInput(reference.getString("artifactId"),reference.getString("sha256")));assertEquals(1,f.sent.get());
        }
    }
    @Test public void oldOrUnboundQqImageCannotBecomePrivateByClaimedOwnerOrLaterNativeBinding()throws Exception {
        try(Fixture f=new Fixture()){JSONObject old=f.receive("old","owner","",f.base+"/image");f.bind();assertThrows(SecurityException.class,()->f.module.handle("ask",new JSONObject().put("eventId",old.getString("id"))));JSONObject other=f.receive("external","other-peer","",f.base+"/image");assertThrows(SecurityException.class,()->f.module.handle("ask",new JSONObject().put("eventId",other.getString("id"))));assertEquals(0,f.cdn.get());assertEquals(0,f.modelCalls.get());assertEquals(0,AttachmentRepository.get(f.android).book.list().length());}
    }
    @Test public void finalSharedDownloaderValidatorKeepsWeixinAndQqCdnNamespacesSeparateBeforeAnyConnection()throws Exception {
        GroupInboundFiles files=new GroupInboundFiles();String weixin="https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param=fixture";assertEquals(weixin,files.checkedAddress(weixin,true).toString());assertThrows(SecurityException.class,()->files.checkedAddress(weixin,false));assertEquals("multimedia.nt.qq.com.cn",files.checkedAddress("https://multimedia.nt.qq.com.cn/download?fixture=1",false).getHost());
        for(String value:new String[]{"https://multimedia.nt.qq.com.cn:444/download","https://multimedia.nt.qq.com.cn.evil.example/download","https://elsewhere.qq.com.cn/download","https://user@multimedia.nt.qq.com.cn/download","http://multimedia.nt.qq.com.cn/download"})assertThrows(SecurityException.class,()->files.checkedAddress(value,false));assertThrows(SecurityException.class,()->files.checkedAddress("https://multimedia.nt.qq.com.cn/download",true));
    }

}
