package dev.pocketengine.features.channels;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.RemoteOwnerWorkflows;
import dev.pocketengine.features.attachments.*;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.features.remote.*;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Real Book/channel/remote SQL/Companion flows. Local HTTP/CDN/model/CLI peers and keystore are synthetic.
 * Existing image fixture is only a controlled peer factory; none of its old test methods is rerun here. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ChannelRemoteInputResultJourneyTest {
    private final WeixinOwnerImageJourneyTest peerFactory=new WeixinOwnerImageJourneyTest();
    @Before public void before(){peerFactory.key();}
    @After public void after(){peerFactory.release();}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception{
        Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(target,args);}catch(InvocationTargetException failed){Throwable cause=failed.getCause();if(cause instanceof Exception)throw(Exception)cause;if(cause instanceof Error)throw(Error)cause;throw failed;}
    }
    private interface FixtureDispatch {void before(String path)throws Exception;}
    private static RemoteModule fixtureModule(FeatureContext context,FixtureDispatch gate)throws Exception {
        Class<?> credentials=Class.forName(RemoteModule.class.getName()+"$CredentialReader"),dispatch=Class.forName(RemoteModule.class.getName()+"$DispatchGate");
        Object token=Proxy.newProxyInstance(RemoteModule.class.getClassLoader(),new Class<?>[]{credentials},(proxy,method,args)->"native-input-files-fixture-token-longer-than-32-characters");
        Object before=Proxy.newProxyInstance(RemoteModule.class.getClassLoader(),new Class<?>[]{dispatch},(proxy,method,args)->{gate.before((String)args[0]);return null;});
        Constructor<RemoteModule> constructor=RemoteModule.class.getDeclaredConstructor(FeatureContext.class,credentials,dispatch);constructor.setAccessible(true);return constructor.newInstance(context,token,before);
    }
    private static Object peers()throws Exception{Class<?> type=Class.forName(WeixinOwnerImageJourneyTest.class.getName()+"$Fixture");Constructor<?> ctor=type.getDeclaredConstructor();ctor.setAccessible(true);return ctor.newInstance();}
    private static JSONObject image(Object peer)throws Exception{
        call(peer,"bind",new Class<?>[]{});call(peer,"enable",new Class<?>[]{boolean.class,boolean.class},true,false);
        Method wire=WeixinOwnerImageJourneyTest.class.getDeclaredMethod("wire",String.class,String.class,String.class,String.class,boolean.class);wire.setAccessible(true);
        JSONObject original=(JSONObject)wire.invoke(null,"2","native-owner@im.wechat","Only this image is selected for the remote task",field(peer,"base")+"/cdn-image",false);
        JSONObject event=(JSONObject)call(peer,"receive",new Class<?>[]{JSONObject.class},original);
        ((ChannelsModule)field(peer,"module")).handle("ask",new JSONObject().put("eventId",event.getString("id")));
        return (JSONObject)call(peer,"await",new Class<?>[]{String.class,String.class},"2","answered");
    }
    static final class RemoteSide implements AutoCloseable {
        final android.content.Context android;final ChannelsModule channel;
        final Path root=Files.createTempDirectory("c3-rebuilt-inputs-native-");
        final Object nativeOwner=new Object();
        final CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();
        final CountDownLatch createEntered=new CountDownLatch(1),releaseCreate=new CountDownLatch(1);
        volatile boolean holdCreate;
        Process server;BufferedReader output;String base,task,window;Path workspace;
        TaskStore tasks;FeatureHost host;RemoteModule remote;RemoteOwnerWorkflows owner;
        AttachmentRepository attachments;AttachmentsModule documents;AttachmentOutcomeHost viewer;
        RemoteSide(android.content.Context android,ChannelsModule channel)throws Exception {this(android,channel,"input_files_fixture_server.py");}
        RemoteSide(android.content.Context android,ChannelsModule channel,String peerScript)throws Exception {this.android=android;this.channel=channel;try{
            Path repo=Path.of(System.getProperty("user.dir"));
            server=new ProcessBuilder("/usr/bin/python3","-B","-u",repo.resolve("companion/"+peerScript).toString(),root.toString())
                    .directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();
            output=new BufferedReader(new InputStreamReader(server.getInputStream(),StandardCharsets.UTF_8));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(!output.ready()&&server.isAlive()&&System.nanoTime()<until)Thread.sleep(5);
            assertTrue(Files.readString(root.resolve("server-stderr.log")),output.ready());
            JSONObject info=new JSONObject(output.readLine());base=info.getString("baseUrl");workspace=Path.of(info.getString("workspace"));boot(true);
        }catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        void boot(boolean first)throws Exception {
            var app=android;
            if(first){app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");}
            tasks=new TaskStore(app);host=new FeatureHost(events::add,tasks,()->false);
            EventSink sink=event->{events.add(event);host.recordEvent(event);};
            FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),sink);
            remote=fixtureModule(context,path->{
                if(path.equals("/v1/jobs")&&task!=null){
                    assertNotNull("Original SQL task must precede the first real HTTP create",tasks.get(task,false));
                    assertEquals("native_remote_private_inputs",tasks.get(task,false).getJSONObject("origin").getString("kind"));
                    if(holdCreate){createEntered.countDown();if(!releaseCreate.await(6,TimeUnit.SECONDS))throw new AssertionError("Fixture create gate not released");}
                }
            });
            host.register(remote);host.register(channel);documents=new AttachmentsModule(context);host.register(documents);
            if(first){
                remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","Controlled input fixture").put("baseUrl",base));
                // Test-only presence record + injected CredentialReader. Not AndroidKeyStore or real credential evidence.
                var slot=RemoteModule.class.getDeclaredMethod("slot",String.class,String.class);slot.setAccessible(true);
                app.getSharedPreferences("feature_secrets",Context.MODE_PRIVATE).edit().putString((String)slot.invoke(null,"computer",base),"fixture-presence-only").commit();
            }
            owner=new RemoteOwnerWorkflows(host,tasks,sink);owner.setForeground(nativeOwner,true);attachments=AttachmentRepository.get(app);

            viewer=new AttachmentOutcomeHost(tasks,attachments,documents,(command,payload,control)->{throw new AssertionError("No media dispatch");});
        }
        JSONObject originalRequest(String worker)throws Exception {
            String binding=remote.profileBindingHash("computer");
            JSONObject env=RemoteTaskRequest.environment(remote.handle("environments",new JSONObject().put("profileId","computer")),"workspace",worker);
            return new JSONObject().put("profileId","computer").put("bindingHash",binding).put("environmentId","workspace").put("environmentBinding",env.getString("bindingHash"))
                    .put("worker",worker).put("readOnly",worker.equals("codex_app_server")).put("timeoutSeconds",30).put("prompt","Read only the explicitly selected input bytes and report their hashes");
        }
        JSONObject open(String worker)throws Exception {
            task=UUID.randomUUID().toString();window=UUID.randomUUID().toString();
            return owner.handle(nativeOwner,"openInputs",new JSONObject().put("windowId",window).put("taskId",task).put("request",originalRequest(worker))).getJSONObject("snapshot");
        }
        JSONObject action(String command,JSONObject fields)throws Exception {
            JSONObject args=new JSONObject().put("taskId",task).put("windowId",window);
            for(var keys=fields.keys();keys.hasNext();){String key=keys.next();args.put(key,fields.get(key));}
            return owner.handle(nativeOwner,command,args).getJSONObject("snapshot");
        }
        JSONObject file(String name,byte[] bytes,JSONObject source)throws Exception {return attachments.book.importBytes(new ByteArrayInputStream(bytes),name,"application/octet-stream",source);}
        JSONObject select(JSONObject... files)throws Exception {
            JSONArray refs=new JSONArray();for(JSONObject file:files)refs.put(new JSONObject().put("id",file.getString("id")).put("sha256",file.getString("sha256")));
            action("selectInputs",new JSONObject().put("files",refs));return action("reviewInputs",new JSONObject());
        }
        JSONObject confirm(JSONObject review)throws Exception {return action("confirmInputs",new JSONObject().put("digest",review.getJSONObject("review").getString("digest")).put("billingAcknowledged",true));}
        JSONObject query()throws Exception{return action("queryInputs",new JSONObject());}
        JSONObject job()throws Exception {JSONObject source=tasks.remoteOwnerTarget(task);return remote.handle("getJob",source);}
        void settled()throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
            while(System.nanoTime()<until){JSONObject actual=job();if(actual.optString("status").equals("completed")&&actual.optBoolean("dispatchFenceFinal")){tasks.reconcileRemoteInputs(task,actual);return;}Thread.sleep(10);}
            throw new AssertionError(tasks.get(task,false));
        }
        int count(String name)throws Exception{return Files.exists(root.resolve(name))?Integer.parseInt(Files.readString(root.resolve(name))):0;}
        void waitMarker(String marker)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!Files.exists(root.resolve(marker))&&System.nanoTime()<until)Thread.sleep(5);assertTrue(marker,Files.exists(root.resolve(marker)));}
        void reopenOriginal()throws Exception {owner.setForeground(nativeOwner,false);owner.close(window);remote.close();host.close();boot(false);window=UUID.randomUUID().toString();owner.handle(nativeOwner,"openInputs",new JSONObject().put("windowId",window).put("taskId",task));}
        SQLiteDatabase db()throws Exception {var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
        public void close()throws Exception {
            releaseCreate.countDown();Files.writeString(root.resolve("release-upload-response"),"cleanup original fixture barrier");
            if(owner!=null){owner.setForeground(nativeOwner,false);if(window!=null)owner.close(window);}if(remote!=null)remote.close();if(host!=null)host.close();
            if(server!=null){server.destroy();if(!server.waitFor(4,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(2,TimeUnit.SECONDS);}assertFalse(server.isAlive());
                if(Files.exists(root.resolve("resource-release.json"))){JSONObject proof=new JSONObject(Files.readString(root.resolve("resource-release.json")));assertTrue(proof.getBoolean("listenerClosed"));assertEquals(0,proof.getInt("activeProcesses"));}}
            if(output!=null)output.close();
        }
    }

    private static JSONObject resultPage(RemoteSide f,JSONObject original)throws Exception {return resultPage(f,original,"proof.txt");}
    private static JSONObject resultPage(RemoteSide f,JSONObject original,String path)throws Exception {
        f.settled();String window=UUID.randomUUID().toString();JSONObject page=f.owner.handle(f.nativeOwner,"open",new JSONObject().put("taskId",f.task).put("windowId",window)).getJSONObject("snapshot");
        JSONObject base=new JSONObject().put("taskId",f.task).put("windowId",window).put("reviewId",page.getString("reviewId"));
        page=f.owner.handle(f.nativeOwner,"listResultFiles",new JSONObject(base.toString()).put("path","")).getJSONObject("snapshot");base.put("reviewId",page.getString("reviewId"));
        page=f.owner.handle(f.nativeOwner,"selectResultFile",new JSONObject(base.toString()).put("path",path)).getJSONObject("snapshot");base.put("reviewId",page.getString("reviewId"));
        page=f.owner.handle(f.nativeOwner,"captureSelectedSnapshot",base).getJSONObject("snapshot");base.put("reviewId",page.getString("reviewId"));
        page=f.owner.handle(f.nativeOwner,"refresh",base).getJSONObject("snapshot");base.put("reviewId",page.getString("reviewId"));
        JSONObject frozen=f.tasks.get(f.task,false).getJSONArray("remoteResultSnapshots").getJSONObject(0);
        return f.owner.handle(f.nativeOwner,"saveSnapshot",new JSONObject(base.toString()).put("snapshotId",frozen.getString("snapshotId")).put("path",path)).getJSONObject("snapshot");
    }
    private static JSONObject returnAction(RemoteSide f,JSONObject page)throws Exception{return new JSONObject().put("taskId",f.task).put("windowId",page.getString("windowId")).put("reviewId",page.getString("reviewId"));}
    private static JSONObject reviewReturn(RemoteSide f,JSONObject page,String input)throws Exception{
        return f.owner.handle(f.nativeOwner,"reviewConversationResult",returnAction(f,page).put("snapshotId",f.tasks.get(f.task,false).getJSONArray("remoteResultSnapshots").getJSONObject(0).getString("snapshotId")).put("path","proof.txt").put("inputAttachmentId",input)).getJSONObject("snapshot");
    }
    private static JSONObject confirmReturn(RemoteSide f,JSONObject page)throws Exception{
        return f.owner.handle(f.nativeOwner,"confirmConversationResult",returnAction(f,page).put("digest",page.getJSONObject("conversationResultReview").getString("digest"))).getJSONObject("snapshot");
    }
    @Test public void selectedOriginalImageReachesOneRemoteWorkerAndExactFrozenTextReturnsThroughOriginalOutboxOnce()throws Exception {
        Object peer=peers();try{JSONObject event=image(peer),input=event.getJSONArray("ownerImageArtifacts").getJSONObject(0);ChannelsModule channel=(ChannelsModule)field(peer,"module");
            try(RemoteSide f=new RemoteSide((Context)field(peer,"android"),channel)){
                f.open("codex_app_server");JSONObject meta=f.attachments.book.get(input.getString("artifactId")),review=f.select(meta);assertEquals(1,review.getJSONArray("channelInputSources").length());
                assertEquals(0,f.count("submit-count"));assertEquals(0,((java.util.List<?>)field(peer,"delivered")).size());f.confirm(review);
                JSONObject page=resultPage(f,event),returnReview=reviewReturn(f,page,meta.getString("id")),scope=returnReview.getJSONObject("conversationResultReview").getJSONObject("scope");
                assertEquals(event.getString("senderId"),scope.getJSONObject("inputSource").getString("senderId"));assertTrue(scope.getString("text").contains(meta.getString("sha256")));assertFalse(scope.getBoolean("automaticSending"));
                JSONObject queuedPage=confirmReturn(f,returnReview),queued=queuedPage.getJSONArray("conversationResultDeliveries").getJSONObject(0);assertEquals("queued",queued.getString("status"));assertEquals(0,((java.util.List<?>)field(peer,"delivered")).size());
                channel.handle("outbox.send",new JSONObject().put("id",queued.getString("id")));JSONObject sent=channel.originalRemoteResults(f.task).getJSONObject(0);assertEquals("sent",sent.getString("status"));
                java.util.List<?> actual=(java.util.List<?>)field(peer,"delivered");assertEquals(1,actual.size());JSONObject payload=(JSONObject)actual.get(0);assertEquals(event.getString("senderId"),payload.getString("to_user_id"));assertEquals("ctx-image-2",payload.getString("context_token"));assertEquals(scope.getString("text"),payload.getJSONArray("item_list").getJSONObject(0).getJSONObject("text_item").getString("text"));
                assertFalse(payload.toString().contains("native-input-files-fixture-token"));assertFalse(payload.toString().contains("00112233445566778899aabbccddeeff"));
                JSONObject again=reviewReturn(f,queuedPage,meta.getString("id"));confirmReturn(f,again);assertEquals(1,channel.originalRemoteResults(f.task).length());assertThrows(IllegalStateException.class,()->channel.handle("outbox.send",new JSONObject().put("id",sent.getString("id"))));assertEquals(1,actual.size());
                assertEquals(1,f.count("submit-count"));assertEquals(1,f.count("put-count"));assertEquals(1,f.count("start-count"));JSONArray observed=new JSONArray(Files.readString(f.workspace.resolve("input-summary.json")));assertEquals(meta.getString("sha256"),observed.getJSONObject(0).getString("sha256"));assertEquals(meta.getLong("size"),observed.getJSONObject(0).getLong("bytes"));
            }
        }finally{((AutoCloseable)peer).close();}
    }
    @Test public void revokedSourceBlocksCreateAndBackgroundReviewCannotQueueThenUnknownActualSendIsNeverReplayed()throws Exception {
        Object peer=peers();try{JSONObject event=image(peer),input=event.getJSONArray("ownerImageArtifacts").getJSONObject(0);ChannelsModule channel=(ChannelsModule)field(peer,"module");
            try(RemoteSide f=new RemoteSide((Context)field(peer,"android"),channel)){
                JSONObject meta=f.attachments.book.get(input.getString("artifactId"));f.open("codex_app_server");JSONObject approved=f.select(meta);channel.handle("owner.unbind",new JSONObject().put("profileId",field(peer,"profile")));f.confirm(approved);assertNull(f.tasks.get(f.task,false));assertEquals(0,f.count("submit-count"));assertEquals(0,f.count("put-count"));assertEquals(0,f.count("start-count"));
            }
        }finally{((AutoCloseable)peer).close();}
        peer=peers();try{JSONObject event=image(peer),input=event.getJSONArray("ownerImageArtifacts").getJSONObject(0);ChannelsModule channel=(ChannelsModule)field(peer,"module");
            try(RemoteSide f=new RemoteSide((Context)field(peer,"android"),channel)){
                JSONObject meta=f.attachments.book.get(input.getString("artifactId"));f.open("codex_app_server");f.confirm(f.select(meta));JSONObject page=resultPage(f,event),review=reviewReturn(f,page,meta.getString("id"));f.owner.setForeground(f.nativeOwner,false);JSONObject stale=review;assertThrows(SecurityException.class,()->confirmReturn(f,stale));assertEquals(0,channel.originalRemoteResults(f.task).length());
                f.owner.setForeground(f.nativeOwner,true);page=f.owner.handle(f.nativeOwner,"refresh",returnAction(f,page)).getJSONObject("snapshot");review=reviewReturn(f,page,meta.getString("id"));JSONObject queuedPage=confirmReturn(f,review),queued=queuedPage.getJSONArray("conversationResultDeliveries").getJSONObject(0);
                HttpServer server=(HttpServer)field(peer,"server");java.util.List<JSONObject> actual=(java.util.List<JSONObject>)field(peer,"delivered");server.createContext("/ilink/bot/sendmessage",exchange->{JSONObject wire=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));actual.add(wire.getJSONObject("msg"));exchange.close();});
                channel.handle("outbox.send",new JSONObject().put("id",queued.getString("id")));assertEquals("unknown",channel.originalRemoteResults(f.task).getJSONObject(0).getString("status"));assertEquals(1,actual.size());
                JSONObject repeated=reviewReturn(f,queuedPage,meta.getString("id"));confirmReturn(f,repeated);assertEquals(1,channel.originalRemoteResults(f.task).length());assertThrows(IllegalStateException.class,()->channel.handle("outbox.send",new JSONObject().put("id",queued.getString("id"))));assertEquals(1,actual.size());
                assertEquals(1,f.count("submit-count"));assertEquals(1,f.count("put-count"));assertEquals(1,f.count("start-count"));
            }
        }finally{((AutoCloseable)peer).close();}
    }
    private static JSONObject selectedOriginalFile(Object peer,byte[] bytes)throws Exception {
        call(peer,"bind",new Class<?>[]{});ChannelsModule module=(ChannelsModule)field(peer,"module");ChannelCore core=(ChannelCore)field(module,"core");String profile=(String)field(peer,"profile");module.handle("profile.save",core.profile(profile).put("ownerMediaEnabled",true));
        byte[] key="0123456789abcdef".getBytes(StandardCharsets.US_ASCII);javax.crypto.Cipher aes=javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");aes.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"));Field download=peer.getClass().getDeclaredField("download");download.setAccessible(true);download.set(peer,aes.doFinal(bytes));
        JSONObject item=new JSONObject().put("type",4).put("file_item",new JSONObject().put("file_name","原始输入.bin").put("len",String.valueOf(bytes.length)).put("media",new JSONObject().put("full_url",field(peer,"base")+"/cdn-image").put("aes_key",java.util.Base64.getEncoder().encodeToString(key)).put("encrypt_type",1)));
        JSONObject wire=new JSONObject().put("message_id","22").put("from_user_id","native-owner@im.wechat").put("message_type",1).put("message_state",2).put("create_time_ms",System.currentTimeMillis()).put("context_token","original-file-context-22").put("item_list",new JSONArray().put(item));JSONObject event=(JSONObject)call(peer,"receive",new Class<?>[]{JSONObject.class},wire);Object nativeOwner=new Object();module.setNativeOwnerForeground(nativeOwner,true);
        try(var window=module.openOwnerMediaWindow(nativeOwner)){var review=module.reviewOwnerMedia(window,event.getString("id"),0);JSONObject saved=module.freezeOwnerMedia(review,review.metadata().getString("digest"),new RunControl()).getJSONObject("attachment");return new JSONObject().put("event",event).put("attachment",saved);}
    }
    @Test public void selectedChannelFileTravelsThroughOneOriginalRemoteJobAndFrozenBinaryReturnsAsActualEncryptedOriginalConversationAttachment()throws Exception {binaryRoundtrip(false,false);}
    @Test public void lostBinaryUploadOrMessageAckKeepsOriginalOutboxUnknownAndNeverRepeatsEitherSideEffect()throws Exception {binaryRoundtrip(true,false);binaryRoundtrip(false,true);}
    private static void binaryRoundtrip(boolean dropUpload,boolean dropMessage)throws Exception {
        Object peer=peers();try{byte[] inputBytes={0,1,(byte)255,8},digest=java.security.MessageDigest.getInstance("SHA-256").digest(inputBytes),outputBytes={0,(byte)255,digest[0],digest[1],digest[2],digest[3]};JSONObject original=selectedOriginalFile(peer,inputBytes),event=original.getJSONObject("event"),meta=original.getJSONObject("attachment");ChannelsModule channel=(ChannelsModule)field(peer,"module");HttpServer transport=(HttpServer)field(peer,"server");java.util.concurrent.atomic.AtomicReference<JSONObject> uploadRequest=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.atomic.AtomicReference<byte[]> uploadedPlain=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.atomic.AtomicInteger uploadCalls=new java.util.concurrent.atomic.AtomicInteger();java.util.List<JSONObject> messages=(java.util.List<JSONObject>)field(peer,"delivered");java.util.concurrent.atomic.AtomicReference<Throwable> fault=(java.util.concurrent.atomic.AtomicReference<Throwable>)field(peer,"serverFailure");
            transport.createContext("/ilink/bot/getuploadurl",exchange->{try{JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));uploadRequest.set(body);assertTrue(exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer "));assertFalse(body.getBoolean("no_need_thumb")==false);assertEquals("native-owner@im.wechat",body.getString("to_user_id"));byte[] response="{\"ret\":0,\"upload_param\":\"fixture-outbound-upload\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}catch(Throwable failed){fault.compareAndSet(null,failed);}finally{exchange.close();}});
            transport.createContext("/c2c/upload",exchange->{try{uploadCalls.incrementAndGet();String authorization=exchange.getRequestHeaders().getFirst("Authorization"),cookie=exchange.getRequestHeaders().getFirst("Cookie");assertTrue(authorization==null||authorization.isEmpty());assertTrue(cookie==null||cookie.isEmpty());assertTrue(exchange.getRequestURI().getRawQuery().contains("fixture-outbound-upload"));JSONObject request=uploadRequest.get();byte[] key=WeixinOwnerImages.key(request.getString("aeskey"),"");byte[] encrypted=exchange.getRequestBody().readAllBytes();assertEquals(request.getLong("filesize"),encrypted.length);uploadedPlain.set(OwnerDmMedia.decrypt(encrypted,key));assertArrayEquals(outputBytes,uploadedPlain.get());if(!dropUpload){exchange.getResponseHeaders().add("x-encrypted-param","fixture-accepted-upload-download-param");exchange.sendResponseHeaders(200,-1);}}catch(Throwable failed){fault.compareAndSet(null,failed);}finally{exchange.close();}});
            if(dropMessage)transport.createContext("/ilink/bot/sendmessage",exchange->{try{messages.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getJSONObject("msg"));}catch(Throwable failed){fault.compareAndSet(null,failed);}finally{exchange.close();}});
            try(RemoteSide f=new RemoteSide((Context)field(peer,"android"),channel,"channel_media_fixture_server.py")){
                f.open("codex_app_server");JSONObject selected=f.select(meta);assertEquals("file",selected.getJSONArray("channelInputSources").getJSONObject(0).getString("mediaKind"));f.confirm(selected);f.settled();assertArrayEquals(outputBytes,Files.readAllBytes(f.workspace.resolve("channel-result.bin")));JSONObject page=resultPage(f,event,"channel-result.bin");JSONObject args=returnAction(f,page).put("snapshotId",f.tasks.get(f.task,false).getJSONArray("remoteResultSnapshots").getJSONObject(0).getString("snapshotId")).put("path","channel-result.bin").put("inputAttachmentId",meta.getString("id"));JSONObject reviewed=f.owner.handle(f.nativeOwner,"reviewConversationAttachment",args).getJSONObject("snapshot");JSONObject scope=reviewed.getJSONObject("conversationResultReview").getJSONObject("scope");assertEquals("exact_binary_attachment_not_text_substitution",scope.getString("resultKind"));assertEquals(outputBytes.length,scope.getLong("bytes"));JSONObject queuedPage=confirmReturn(f,reviewed);JSONObject queued=queuedPage.getJSONArray("conversationResultDeliveries").getJSONObject(0);assertEquals(0,uploadCalls.get());assertEquals(0,messages.size());channel.handle("outbox.send",new JSONObject().put("id",queued.getString("id")));JSONObject actual=channel.originalRemoteResults(f.task).getJSONObject(0);assertEquals(dropUpload||dropMessage?"unknown":"sent",actual.getString("status"));assertEquals(dropUpload?"uploading":"message_sending",actual.getString("mediaPhase"));assertEquals(1,uploadCalls.get());assertEquals(dropUpload?0:1,messages.size());assertArrayEquals(outputBytes,uploadedPlain.get());assertEquals(outputBytes.length,uploadRequest.get().getLong("rawsize"));
                if(!dropUpload){JSONObject sent=messages.get(0);assertEquals(event.getString("senderId"),sent.getString("to_user_id"));assertEquals("original-file-context-22",sent.getString("context_token"));JSONObject item=sent.getJSONArray("item_list").getJSONObject(0);assertEquals(4,item.getInt("type"));assertEquals(String.valueOf(outputBytes.length),item.getJSONObject("file_item").getString("len"));assertEquals("fixture-accepted-upload-download-param",item.getJSONObject("file_item").getJSONObject("media").getString("encrypt_query_param"));}
                assertThrows(IllegalStateException.class,()->channel.handle("outbox.send",new JSONObject().put("id",queued.getString("id"))));if(dropUpload||dropMessage)assertThrows(SecurityException.class,()->channel.handle("outbox.resolve",new JSONObject().put("id",queued.getString("id")).put("outcome","not_delivered").put("confirmed",true).put("note","No message visible is not proof that upload never happened")));assertEquals(1,uploadCalls.get());assertEquals(1,f.count("submit-count"));assertEquals(1,f.count("put-count"));assertEquals(1,f.count("start-count"));assertFalse(actual.toString().contains(uploadRequest.get().getString("aeskey")));assertFalse(actual.toString().contains("fixture-accepted-upload-download-param"));assertFalse(actual.toString().contains("fixture-outbound-upload"));
            }
        }finally{((AutoCloseable)peer).close();}
    }

}
