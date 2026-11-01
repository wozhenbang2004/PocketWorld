package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;

/** Original production iLink, vault, attachment bytes, model admission and outbox; controlled
 * HTTP/CDN/account-port fixtures only, never a real account, remote CDN, scan or device assertion. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class WeixinOwnerImageJourneyTest {
    private static final String HEX="00112233445566778899aabbccddeeff",TOKEN="fixture-owner-image-bot-token";
    private static final byte[] PNG=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMwbzr5HwAE+AKC+5SnugAAAABJRU5ErkJggg==");
    @Before public void key(){android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(PNG,0,PNG.length,bounds);System.out.println("actual native PNG bounds="+bounds.outWidth+"x"+bounds.outHeight+" mime="+bounds.outMimeType);assertEquals(1,bounds.outWidth);assertEquals(1,bounds.outHeight);assertEquals("image/png",bounds.outMimeType);java.security.Security.addProvider(new java.security.Provider("WeixinImageKeyStore","1","Explicit fixture"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void release(){ModelGateway.installAccountInferencePort(null);java.security.Security.removeProvider("WeixinImageKeyStore");}
    private static byte[] encrypted(byte[] plain)throws Exception {Cipher aes=Cipher.getInstance("AES/ECB/PKCS5Padding");aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(WeixinOwnerImages.key(HEX,""),"AES"));return aes.doFinal(plain);}
    private static String hash(byte[] bytes)throws Exception {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static ChannelCore core(ChannelsModule module)throws Exception {var field=ChannelsModule.class.getDeclaredField("core");field.setAccessible(true);return (ChannelCore)field.get(module);}
    private static JSONObject wire(String id,String sender,String text,String imageUrl,boolean unknown)throws Exception {
        JSONArray items=new JSONArray();if(text!=null&&!text.isEmpty())items.put(new JSONObject().put("type",1).put("text_item",new JSONObject().put("text",text)));
        if(imageUrl!=null)items.put(new JSONObject().put("type",2).put("image_item",new JSONObject().put("aeskey",HEX).put("media",new JSONObject().put("full_url",imageUrl).put("aes_key",Base64.getEncoder().encodeToString("incorrect-fallback".getBytes(StandardCharsets.UTF_8))).put("encrypt_type",1))));
        if(unknown)items.put(new JSONObject().put("type",5).put("video_item",new JSONObject().put("media",new JSONObject().put("aes_key","fixture-unsupported-video-secret"))));
        return new JSONObject().put("message_id",id).put("from_user_id",sender).put("message_type",1).put("message_state",2).put("create_time_ms",System.currentTimeMillis()).put("context_token","ctx-image-"+id).put("item_list",items);
    }
    private static Context isolatedContext(){Context original=RuntimeEnvironment.getApplication();java.io.File files=new java.io.File(original.getFilesDir(),"owner-image-fixture-"+UUID.randomUUID());assertTrue(files.mkdirs());return new ContextWrapper(original){@Override public java.io.File getFilesDir(){return files;}@Override public Context getApplicationContext(){return this;}};}
    static final class Fixture implements AutoCloseable {
        final Context android=isolatedContext();final FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),ignored->{});
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final AtomicReference<JSONObject> incoming=new AtomicReference<>();final AtomicInteger cdn=new AtomicInteger(),models=new AtomicInteger();final CopyOnWriteArrayList<JSONObject> requests=new CopyOnWriteArrayList<>(),delivered=new CopyOnWriteArrayList<>();final AtomicReference<Throwable> serverFailure=new AtomicReference<>();final CountDownLatch cdnEntered=new CountDownLatch(1),cdnRelease=new CountDownLatch(1);volatile boolean holdCdn,redirectCdn;volatile byte[] download=encrypted(PNG);final String base;ChannelsModule module;String profile,bindingEvent;int cursor;
        Fixture()throws Exception {
            server.createContext("/",exchange->{try{String path=exchange.getRequestURI().getPath();byte[] raw=exchange.getRequestBody().readAllBytes();JSONObject body=raw.length==0?new JSONObject():new JSONObject(new String(raw,StandardCharsets.UTF_8));byte[] output;
                if(path.equals("/cdn-image")){String authorization=exchange.getRequestHeaders().getFirst("Authorization"),cookie=exchange.getRequestHeaders().getFirst("Cookie");assertTrue(authorization==null||authorization.isEmpty());assertTrue(cookie==null||cookie.isEmpty());cdn.incrementAndGet();cdnEntered.countDown();if(holdCdn)assertTrue(cdnRelease.await(5,TimeUnit.SECONDS));if(redirectCdn){exchange.getResponseHeaders().add("Location",base()+"/must-not-follow");exchange.sendResponseHeaders(302,-1);return;}output=download;}
                else {String response;
                    if(path.equals("/ilink/bot/get_bot_qrcode"))response=new JSONObject().put("qrcode","fixture-image-login").put("qrcode_img_content","FIXTURE-NOT-LOGIN").toString();
                    else if(path.equals("/ilink/bot/get_qrcode_status"))response=new JSONObject().put("status","confirmed").put("bot_token",TOKEN).put("ilink_bot_id","fixture-image-bot@im.bot").put("baseurl",WeixinClient.DEFAULT_BASE).toString();
                    else if(path.equals("/ilink/bot/getupdates")){assertEquals("Bearer "+TOKEN,exchange.getRequestHeaders().getFirst("Authorization"));response=new JSONObject().put("ret",0).put("get_updates_buf","image-cursor-"+(++cursor)).put("msgs",incoming.get()==null?new JSONArray():new JSONArray().put(incoming.get())).toString();}
                    else if(path.equals("/ilink/bot/sendmessage")){assertEquals("Bearer "+TOKEN,exchange.getRequestHeaders().getFirst("Authorization"));delivered.add(body.getJSONObject("msg"));response="{\"ret\":0}";}
                    else if(path.equals("/v1/chat/completions")){models.incrementAndGet();requests.add(body);assertFalse(body.has("tools"));assertFalse(body.toString().contains(TOKEN));assertFalse(body.toString().contains(HEX));assertFalse(body.toString().contains("/cdn-image"));assertFalse(body.toString().contains("ctx-image-"));response=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content","已依据本次明确提供的输入回答")))).toString();}
                    else throw new AssertionError("unexpected owner image fixture route "+path);
                    output=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");
                }
                exchange.sendResponseHeaders(200,output.length);exchange.getResponseBody().write(output);
            }catch(Throwable failure){serverFailure.compareAndSet(null,failure);}finally{exchange.close();}});server.start();base="http://127.0.0.1:"+server.getAddress().getPort();model(true);open();String login=module.handle("weixin.login.start",new JSONObject().put("name","Original image fixture")).getString("loginId");profile=module.handle("weixin.login.poll",new JSONObject().put("loginId",login)).getJSONObject("profile").getString("id");
        }
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void open()throws Exception{module=new ChannelsModule(context,new ChannelHttp(base()),new QqGatewayClient(true),new WeixinClient(base()),new GroupInboundFiles(true));}
        void model(boolean images)throws Exception{context.settings.save(new JSONObject().put("baseUrl",base()+"/v1").put("provider","openai_chat").put("model","native-image-model").put("supportsImageInput",images).put("maxOutputTokens",256).put("maxSteps",8));}
        JSONObject receive(JSONObject message)throws Exception{incoming.set(message);module.handle("weixin.poll",new JSONObject().put("id",profile));return event(message.getString("message_id"));}
        JSONObject event(String id)throws Exception{JSONArray inbox=module.snapshot().getJSONArray("inbox");for(int i=0;i<inbox.length();i++)if(inbox.getJSONObject(i).getString("messageId").equals("wx-"+id))return inbox.getJSONObject(i);throw new AssertionError("missing original image event");}
        void bind()throws Exception{bindingEvent=receive(wire("1","native-owner@im.wechat","Native binding source",null,false)).getString("id");module.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",bindingEvent).put("confirmed",true));}
        void enable(boolean images,boolean auto)throws Exception{JSONObject saved=core(module).profile(profile);saved.put("ownerImagesEnabled",images).put("autoReply",auto);module.handle("profile.save",saved);}
        JSONObject await(String id,String status)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(true){JSONObject event=event(id);if(event.optString("status").equals(status)){var active=ChannelsModule.class.getDeclaredField("answering");active.setAccessible(true);if(!((java.util.Map<?,?>)active.get(module)).containsKey(event.getString("id")))return event;}if(System.nanoTime()>deadline)fail("owner image did not reach "+status+": "+event);Thread.sleep(5);}}
        void sent(int count)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(true){int sent=0;JSONArray out=module.snapshot().getJSONArray("outbox");for(int i=0;i<out.length();i++)if(out.getJSONObject(i).optString("status").equals("sent"))sent++;if(sent==count)return;if(System.nanoTime()>deadline)fail("image reply did not settle: "+out+" inbox="+module.snapshot().getJSONArray("inbox"));Thread.sleep(5);}}
        @Override public void close()throws Exception{cdnRelease.countDown();module.close();server.stop(0);Throwable failure=serverFailure.get();if(failure!=null&&!(failure instanceof java.io.IOException))throw new AssertionError("fixture HTTP failed",failure);}
    }
    @Test public void pureImagesRetainEmptyCurrentTextAndNeverCopyDownloadKeysIntoNativeInboxData()throws Exception {
        JSONObject canonical=WeixinClient.canonical(wire("2","native-owner@im.wechat",null,"https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param=fixture-sensitive",true));assertNotNull(canonical);assertEquals("",canonical.getString("text"));assertEquals(1,ChannelMessageParts.weixinImagePositions(canonical.getJSONObject("messageParts")).length());assertTrue(canonical.getBoolean("partialInput"));String safe=canonical.toString();assertFalse(safe.contains(HEX));assertFalse(safe.contains("fixture-sensitive"));assertFalse(safe.contains("fixture-unsupported-video-secret"));assertNull(WeixinClient.canonical(wire("2","native-owner@im.wechat",null,"https://novac2c.cdn.weixin.qq.com/c2c/download?a=b",false).put("group_id","not-supported-group")));
    }
    @Test public void nativeProtocolKeyRepresentationsAndPaddingAreExactNotModelDeclared()throws Exception {
        byte[] expected=WeixinOwnerImages.key(HEX,"");assertArrayEquals(expected,WeixinOwnerImages.key("",Base64.getEncoder().encodeToString(expected)));assertArrayEquals(expected,WeixinOwnerImages.key("",Base64.getEncoder().encodeToString(HEX.getBytes(StandardCharsets.US_ASCII))));assertArrayEquals(expected,WeixinOwnerImages.key(HEX,"bad fallback is not used"));assertArrayEquals(PNG,WeixinOwnerImages.decrypt(encrypted(PNG),expected));assertArrayEquals(PNG,WeixinOwnerImages.decrypt(PNG,new byte[0]));assertThrows(IllegalArgumentException.class,()->WeixinOwnerImages.key("not-a-key",""));assertThrows(IllegalArgumentException.class,()->WeixinOwnerImages.decrypt(new byte[17],expected));assertThrows(IllegalArgumentException.class,()->WeixinOwnerImages.imageFacts("not an image".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void officialCdnScopeRefusesCredentialsRedirectTargetsAndNonstandardPortsBeforeNetwork()throws Exception {
        GroupInboundFiles transport=new GroupInboundFiles();assertEquals("https://novac2c.cdn.weixin.qq.com/c2c/download?a=b",transport.weixinAddress("https://novac2c.cdn.weixin.qq.com/c2c/download?a=b"));for(String url:new String[]{"http://novac2c.cdn.weixin.qq.com/c2c/download?a=b","https://novac2c.cdn.weixin.qq.com:444/c2c/download?a=b","https://user@novac2c.cdn.weixin.qq.com/c2c/download?a=b","https://novac2c.cdn.weixin.qq.com/c2c/../private?a=b","https://evil.example/c2c/download?a=b","http://127.0.0.1/cdn-image"})assertThrows(SecurityException.class,()->transport.weixinAddress(url));
    }
    @Test public void nativeOwnerPureImageUsesActualDecryptedOriginalBytesThenColdReadbackKeepsThatVersionAndDuplicateDoesNotResend()throws Exception {
        try(Fixture f=new Fixture()){
            f.bind();f.enable(true,true);JSONObject current=f.receive(wire("2","native-owner@im.wechat",null,f.base+"/cdn-image",true));f.sent(1);JSONObject event=f.event("2");assertEquals("model_response_received",event.getString("imageInputState"));JSONObject ref=event.getJSONArray("ownerImageArtifacts").getJSONObject(0);assertEquals(hash(PNG),ref.getString("sha256"));assertEquals(PNG.length,ref.getLong("bytes"));assertEquals("image/png",ref.getString("mimeType"));assertEquals(1,ref.getInt("width"));assertEquals(1,f.cdn.get());assertEquals(1,f.models.get());assertWireImage(f.requests.get(0),PNG);assertFalse(event.toString().contains(HEX));assertFalse(event.toString().contains("/cdn-image"));assertEquals("ctx-image-2",f.delivered.get(0).getString("context_token"));String reply=f.delivered.get(0).getJSONArray("item_list").getJSONObject(0).getJSONObject("text_item").getString("text");assertTrue(reply.contains("1张"));assertTrue(reply.contains("其他未支持项"));assertEquals(0,f.module.snapshot().getJSONArray("taskRequests").length());assertFalse(f.module.snapshot().getJSONArray("bindings").getJSONObject(0).getBoolean("ownerTasksEnabled"));
            f.module.handle("weixin.poll",new JSONObject().put("id",f.profile));assertEquals(1,f.cdn.get());assertEquals(1,f.models.get());assertEquals(1,f.delivered.size());
            f.module.close();f.open();f.download="different mutable CDN version".getBytes(StandardCharsets.UTF_8);f.module.handle("ask",new JSONObject().put("eventId",current.getString("id")));f.await("2","answered");assertEquals(1,f.cdn.get());assertEquals(2,f.models.get());assertWireImage(f.requests.get(1),PNG);assertEquals(1,f.delivered.size());assertEquals("queued",f.module.snapshot().getJSONArray("outbox").getJSONObject(1).getString("status"));assertArrayEquals(PNG,AttachmentRepository.get(f.context.android).book.readVerifiedSnapshot(ref.getString("artifactId"),ref.getString("sha256"),new RunControl()));
        }
    }
    @Test public void imageConsentAndReceiptTimeOwnerBindingCannotBeForgedOrInheritedByAnotherDm()throws Exception {
        try(Fixture f=new Fixture()){
            assertFalse(core(f.module).profile(f.profile).getBoolean("ownerImagesEnabled"));JSONObject old=f.receive(wire("2","native-owner@im.wechat",null,f.base+"/cdn-image",false));f.bind();f.enable(false,true);f.receive(wire("3","native-owner@im.wechat",null,f.base+"/cdn-image",false));assertEquals("awaiting_local",f.event("3").getString("status"));assertEquals(0,f.models.get());assertEquals(0,f.cdn.get());
            f.enable(true,true);assertThrows(SecurityException.class,()->f.module.handle("ask",new JSONObject().put("eventId",old.getString("id"))));f.receive(wire("4","another-peer@im.wechat","Only this text may be answered",f.base+"/cdn-image",false));f.sent(1);assertEquals(0,f.cdn.get());assertEquals(1,f.models.get());JSONArray messages=f.requests.get(0).getJSONArray("messages");assertTrue(messages.getJSONObject(messages.length()-1).get("content") instanceof String);assertEquals(0,AttachmentRepository.get(f.context.android).book.list().length());assertFalse(f.event("4").has("ownerImageArtifacts"));
        }
    }
    @Test public void nativeModelImageCapabilityIsRequiredBeforeCdnEvenWhenIncomingPayloadClaimsSupport()throws Exception {
        try(Fixture f=new Fixture()){
            f.bind();f.enable(true,true);f.model(false);JSONObject message=wire("2","native-owner@im.wechat","Inspect this image",f.base+"/cdn-image",false).put("supportsImageInput",true).put("privateOwner",true);f.receive(message);f.await("2","failed");assertEquals(0,f.cdn.get());assertEquals(0,f.models.get());assertEquals(0,f.delivered.size());assertEquals(0,AttachmentRepository.get(f.context.android).book.list().length());
            f.model(true);f.redirectCdn=true;f.receive(wire("3","native-owner@im.wechat","Do not follow a CDN redirect",f.base+"/cdn-image",false));f.await("3","failed");assertEquals(1,f.cdn.get());assertEquals(0,f.models.get());assertEquals(0,f.delivered.size());
        }
    }
    @Test public void unbindingDuringOriginalCdnReadCannotFreezePrivateBytesOrCallModelAndOldMessageCannotBePromotedByRebind()throws Exception {
        try(Fixture f=new Fixture()){
            f.bind();f.enable(true,true);f.holdCdn=true;JSONObject event=f.receive(wire("2","native-owner@im.wechat",null,f.base+"/cdn-image",false));assertTrue(f.cdnEntered.await(5,TimeUnit.SECONDS));f.module.handle("owner.unbind",new JSONObject().put("profileId",f.profile));f.cdnRelease.countDown();f.await("2","cancelled");assertEquals(0,f.models.get());assertEquals(0,f.delivered.size());assertEquals(0,AttachmentRepository.get(f.context.android).book.list().length());JSONObject fresh=f.receive(wire("3","native-owner@im.wechat",null,f.base+"/cdn-image",false));f.module.handle("owner.bind",new JSONObject().put("profileId",f.profile).put("verifiedEventId",fresh.getString("id")).put("confirmed",true));assertThrows(SecurityException.class,()->f.module.handle("ask",new JSONObject().put("eventId",event.getString("id"))));assertEquals(1,f.cdn.get());
        }
    }
    @Test public void originalAttachmentByteChangeAtPhysicalAccountAdmissionPreventsModelWriteOrReply()throws Exception {
        try(Fixture f=new Fixture()){
            f.bind();f.enable(true,true);AtomicInteger entries=new AtomicInteger(),writes=new AtomicInteger();f.context.settings.selectAccountModel(new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId","fixture-image-account").put("accountVersion",1L).put("modelId","fixture-image-model"));
            ModelGateway.installAccountInferencePort(new ModelGateway.AccountInferencePort(){
                public boolean imageInputSupported(ModelConfig.AccountModelRef ref){return true;}
                public void stream(ModelConfig.AccountModelRef ref,ResponsesRequest req,GatewayCall operation,ChatGptProvider.EventSink sink)throws GatewayFailure{throw new GatewayFailure("UNEXPECTED_LEGACY_FIXTURE");}
                public void streamWithVersion(ModelConfig.AccountModelRef ref,ResponsesRequest req,GatewayCall operation,ModelGateway.AccountVersionSink version,ChatGptProvider.EventSink sink)throws GatewayFailure {entries.incrementAndGet();try{JSONObject image=f.event("2").getJSONArray("ownerImageArtifacts").getJSONObject(0);byte[] changed=PNG.clone();changed[changed.length-1]^=1;Files.write(AttachmentRepository.get(f.context.android).book.file(image.getString("artifactId")).toPath(),changed);version.authorized(ref.accountVersion);operation.check();writes.incrementAndGet();throw new GatewayFailure("IMAGE_MUTATION_WAS_NOT_FENCED");}catch(GatewayFailure known){throw known;}catch(Exception denied){throw new GatewayFailure("ORIGINAL_IMAGE_ADMISSION_DENIED");}}
            });
            f.receive(wire("2","native-owner@im.wechat",null,f.base+"/cdn-image",false));f.await("2","failed");assertEquals(f.event("2").toString(),1,entries.get());assertEquals(0,writes.get());assertEquals(0,f.models.get());assertEquals(0,f.delivered.size());assertEquals(1,f.cdn.get());assertEquals("model_result_unconfirmed",f.event("2").getString("imageInputState"));
        }
    }
    private static void assertWireImage(JSONObject request,byte[] expected)throws Exception {JSONArray messages=request.getJSONArray("messages");JSONArray parts=messages.getJSONObject(messages.length()-1).getJSONArray("content");int images=0;for(int i=0;i<parts.length();i++)if(parts.getJSONObject(i).optString("type").equals("image_url")){String url=parts.getJSONObject(i).getJSONObject("image_url").getString("url");assertTrue(url.startsWith("data:image/png;base64,"));assertArrayEquals(expected,Base64.getDecoder().decode(url.substring(url.indexOf(',')+1)));images++;}assertEquals(1,images);}
}
