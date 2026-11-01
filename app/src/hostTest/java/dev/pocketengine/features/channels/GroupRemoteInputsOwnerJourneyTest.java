package dev.pocketengine.features.channels;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import androidx.activity.ComponentActivity;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.NativeGroupOwnerWorkflows;
import dev.pocketengine.features.RemoteOwnerWorkflows;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.groups.GroupsModule;
import dev.pocketengine.features.groups.host.GroupArtifactStore;
import dev.pocketengine.features.groups.host.GroupSafeCapabilities;
import dev.pocketengine.features.groups.host.GroupTaskHost;
import dev.pocketengine.features.remote.RemoteAssignmentFixtureModule;
import dev.pocketengine.features.remote.RemoteModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Actual signed group -> original group artifacts/SQL -> original binary job -> native owner -> same group outbox.
 * Only synthetic loopback provider/CLI/account facts. No external account login or actual group message. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class GroupRemoteInputsOwnerJourneyTest {
    public static final class OwnerActivity extends ComponentActivity {}
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("GroupRemoteFixtureKeys","1","Synthetic local key store only"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void removeKeys(){java.security.Security.removeProvider("GroupRemoteFixtureKeys");}
    interface Checked<T>{T run()throws Exception;}
    static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();
        final ActivityController<OwnerActivity> activity=Robolectric.buildActivity(OwnerActivity.class).setup();
        final Path root=Files.createTempDirectory("c3-group-original-input-");
        final NativeGroupOwnerWorkflows nativeGroup=new NativeGroupOwnerWorkflows(app,OwnerActivity.class);
        final ExecutorService nativeQueue=Executors.newSingleThreadExecutor();
        final LinkedBlockingQueue<Runnable> originalQueue=new LinkedBlockingQueue<>(20);
        final ConcurrentHashMap<String,RunControl> controls=new ConcurrentHashMap<>();
        final CopyOnWriteArrayList<JSONObject> replies=new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> fault=new AtomicReference<>();final AtomicReference<byte[]> uploaded=new AtomicReference<>();
        final AtomicInteger uploads=new AtomicInteger(),models=new AtomicInteger(),downloads=new AtomicInteger();
        final byte[] raw=new byte[]{0,(byte)255,3,(byte)128,10,65,0,42};
        final String secret="synthetic-native-group-source-secret";
        TaskStore tasks;FeatureHost features;ChannelsModule channels;GroupsModule groups;GroupTaskHost groupHost;GroupArtifactStore artifacts;RemoteModule remote;RemoteOwnerWorkflows remoteOwner;HttpServer api;Process companion;BufferedReader stdout;
        String base,remoteBase,window,profile,groupId,request,parent,page,child;JSONObject group;volatile boolean dropUploadAck;
        Fixture()throws Exception {try{
            app.deleteDatabase("pocket-world.db");app.deleteFile("remote-profiles.json");app.deleteFile("remote-operations.json");
            Path repo=Path.of(System.getProperty("user.dir"));companion=new ProcessBuilder("/usr/bin/python3","-B","-u",repo.resolve("companion/group_input_fixture_server.py").toString(),root.toString()).directory(repo.resolve("companion").toFile()).redirectError(root.resolve("server-stderr.log").toFile()).start();
            stdout=new BufferedReader(new InputStreamReader(companion.getInputStream(),StandardCharsets.UTF_8));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(!stdout.ready()&&companion.isAlive()&&System.nanoTime()<until)Thread.sleep(5);assertTrue(Files.readString(root.resolve("server-stderr.log")),stdout.ready());remoteBase=new JSONObject(stdout.readLine()).getString("baseUrl");
            tasks=new TaskStore(app);features=new FeatureHost(e->{},tasks,()->false);SettingsStore settings=new SettingsStore(app);
            api=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);api.createContext("/",x->{try{
                String path=x.getRequestURI().getPath();byte[] wire=x.getRequestBody().readAllBytes();JSONObject body=wire.length==0?new JSONObject():new JSONObject(new String(wire,StandardCharsets.UTF_8));byte[] answer;
                if(path.equals("/v1/chat/completions")){models.incrementAndGet();assertFalse(body.toString().contains("PRIVATE OWNER SENTINEL"));answer=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content","原认证群文件已接收。任何远端外传和成果回群仍需要主人另审。")))).toString().getBytes(StandardCharsets.UTF_8);}
                else if(path.equals("/app/getAppAccessToken"))answer=new JSONObject().put("access_token","synthetic-group-token").put("expires_in",7200).toString().getBytes(StandardCharsets.UTF_8);
                else if(path.equals("/input.bin")){downloads.incrementAndGet();assertTrue("No credential may be forwarded to group input CDN",x.getRequestHeaders().getFirst("Authorization")==null||x.getRequestHeaders().getFirst("Authorization").isEmpty());answer=raw;x.getResponseHeaders().add("Content-Type","application/octet-stream");}
                else if(path.equals("/v2/groups/room-native/files")){uploads.incrementAndGet();assertEquals("QQBot synthetic-group-token",x.getRequestHeaders().getFirst("Authorization"));assertFalse(body.getBoolean("srv_send_msg"));uploaded.set(Base64.getDecoder().decode(body.getString("file_data")));if(dropUploadAck){dropUploadAck=false;x.close();return;}answer=new JSONObject().put("file_info","actual-group-opaque-upload").toString().getBytes(StandardCharsets.UTF_8);}
                else if(path.equals("/v2/groups/room-native/messages")){assertEquals("QQBot synthetic-group-token",x.getRequestHeaders().getFirst("Authorization"));replies.add(body);answer=new JSONObject().put("id","native-group-message-"+replies.size()).toString().getBytes(StandardCharsets.UTF_8);}
                else throw new AssertionError("No route outside the original exact group is allowed: "+path);
                x.sendResponseHeaders(200,answer.length);x.getResponseBody().write(answer);
            }catch(Throwable bad){fault.compareAndSet(null,bad);}finally{x.close();}});api.start();base="http://127.0.0.1:"+api.getAddress().getPort();
            FeatureContext context=new FeatureContext(app,settings,new SecretVault(app),e->features.recordEvent(e));
            channels=new ChannelsModule(context,new ChannelHttp(base),new QqGatewayClient(true),new WeixinClient(),new GroupInboundFiles(true));features.register(channels);
            remote=RemoteAssignmentFixtureModule.open(context);features.register(remote);remote.handle("saveProfile",new JSONObject().put("id","selected-computer").put("name","Explicit synthetic group API-mode computer").put("baseUrl",remoteBase));RemoteAssignmentFixtureModule.markConfigured(app,"selected-computer",remoteBase);
            remoteOwner=new RemoteOwnerWorkflows(features,tasks,e->features.recordEvent(e));remoteOwner.setForeground(activity.get(),true);
            artifacts=new GroupArtifactStore(root.resolve("groups").toFile());ModelConfig config=new ModelConfig(base+"/v1","","group-inbound-model",false,8,10,"openai_chat",256);
            groupHost=new GroupTaskHost(tasks,r->{if(!originalQueue.offer(r))throw new RejectedExecutionException();},controls,()->config,new GroupSafeCapabilities.Services(artifacts,null,null),id->{},e->{});
            GroupChannelBridge bridge=channels.groupBridge();groups=new GroupsModule(root.resolve("groups").toFile(),bridge,groupHost,bridge,nativeGroup);bridge.bind(groups,r->{if(!originalQueue.offer(r))throw new RejectedExecutionException();});bridge.bindArtifacts(artifacts);groups.bindRemoteInputArtifacts(artifacts);nativeGroup.bind(groups);nativeGroup.bindMaterials(groupHost,tasks,channels,()->config);nativeGroup.bindRemoteInputs(remote,remoteOwner,artifacts);window=nativeGroup.open(activity.get());
            profile=channels.handle("profile.save",new JSONObject().put("name","Authenticated original group input").put("provider","qq").put("appId","group-remote-bot").put("secret",secret).put("enabled",true)).getString("id");
            group=command("group.create",new JSONObject().put("name","Original exact group").put("provider","qq").put("accountId",profile).put("roomId","room-native"));groupId=group.getString("id");group=command("member.set",new JSONObject().put("id",groupId).put("revision",group.getLong("revision")).put("senderId","source-member").put("displayName","Authenticated source member, not owner").put("role","member").put("enabled",true));
            JSONObject quota=group.getJSONObject("quota");quota.put("dailyTokens",200000).put("maxTokensPerTask",60000);group=command("policy.set",new JSONObject().put("id",groupId).put("revision",group.getLong("revision")).put("enabled",true).put("visibility","members").put("quota",quota).put("grants",new JSONArray().put(new JSONObject().put("capability","group.files").put("resource",group.getString("spaceId")).put("expiresAt",System.currentTimeMillis()+3_600_000).put("allowGroupAudience",true))));
            channels.handle("server.start",new JSONObject().put("port",0));receive("source-message",true,"/file --tokens=60000 Use only this authenticated group file");drain();assertEquals(1,models.get());assertEquals(1,downloads.get());assertEquals(tasks.list().toString(),1,tasks.list().length());JSONObject original=tasks.get(tasks.list().getJSONObject(0).getString("id"),false);parent=original.getString("id");request=original.getJSONObject("origin").getString("requestId");assertEquals(original.toString(),"completed",original.getString("status"));
            // No result sink: this fixture intentionally observes zero automatic group sends.
            page=owner("remoteInputs.openPage",new JSONObject().put("requestId",request)).getString("pageId");
        }catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        <T>T work(Checked<T> action)throws Exception{try{return nativeQueue.submit(action::run).get(15,TimeUnit.SECONDS);}catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof Exception)throw(Exception)cause;if(cause instanceof Error)throw(Error)cause;throw failed;}}
        JSONObject command(String command,JSONObject value)throws Exception{return work(()->nativeGroup.handle(window,command,value));}
        JSONObject owner(String action,JSONObject value)throws Exception{return work(()->nativeGroup.call(window,()->nativeGroup.remoteInputs(action,value)));}
        JSONObject action(String action,JSONObject value)throws Exception{return owner("remoteInputs."+action,new JSONObject(value.toString()).put("pageId",page));}
        void drain(){int n=0;Runnable job;while((job=originalQueue.poll())!=null){if(++n>12)throw new AssertionError("Unexpected extra original runner");job.run();}}
        JSONObject receive(String message,boolean withFile,String text)throws Exception{
            JSONObject data=new JSONObject().put("id",message).put("group_openid","room-native").put("content",text).put("timestamp",Instant.now().toString()).put("author",new JSONObject().put("member_openid","source-member"));if(withFile)data.put("attachments",new JSONArray().put(new JSONObject().put("url",base+"/input.bin").put("content_type","application/octet-stream").put("size",raw.length)));
            byte[] body=new JSONObject().put("op",0).put("t","GROUP_AT_MESSAGE_CREATE").put("d",data).toString().getBytes(StandardCharsets.UTF_8);String stamp=String.valueOf(System.currentTimeMillis()/1000);byte[] pre=stamp.getBytes(StandardCharsets.UTF_8),sign=new byte[pre.length+body.length];System.arraycopy(pre,0,sign,0,pre.length);System.arraycopy(body,0,sign,pre.length,body.length);
            int port=channels.snapshot().getJSONObject("server").getInt("port");HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/events/"+profile).openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("x-bot-appid","group-remote-bot");c.setRequestProperty("x-signature-timestamp",stamp);c.setRequestProperty("x-signature-ed25519",ChannelProtocol.hex(ChannelProtocol.sign(secret,sign)));c.setFixedLengthStreamingMode(body.length);try(var out=c.getOutputStream()){out.write(body);}int status=c.getResponseCode();String rawReply=new String((status==200?c.getInputStream():c.getErrorStream()).readAllBytes(),StandardCharsets.UTF_8);c.disconnect();assertEquals(rawReply,200,status);return new JSONObject(rawReply);
        }
        JSONObject review()throws Exception{
            JSONObject env=remote.handle("environments",new JSONObject().put("profileId","selected-computer")).getJSONArray("environments").getJSONObject(0);
            JSONObject request=new JSONObject().put("profileId","selected-computer").put("bindingHash",remote.profileBindingHash("selected-computer")).put("environmentId","group-workspace").put("environmentBinding",env.getString("bindingHash")).put("worker","codex_app_server").put("readOnly",true).put("prompt","Read only the explicitly selected group input; return a bounded factual description.").put("timeoutSeconds",30);
            JSONObject opened=action("open",new JSONObject().put("request",request));child=opened.getJSONObject("input").getString("taskId");JSONArray files=opened.getJSONObject("input").getJSONArray("availableFiles");assertEquals(1,files.length());JSONObject file=files.getJSONObject(0);assertTrue(file.getBoolean("groupSource"));assertEquals(raw.length,file.getLong("size"));
            input("selectInputs",new JSONObject().put("files",new JSONArray().put(new JSONObject().put("id",file.getString("id")).put("sha256",file.getString("sha256")))));return input("reviewInputs",new JSONObject());
        }
        JSONObject input(String command,JSONObject values)throws Exception{return action("input",new JSONObject().put("command",command).put("payload",values));}
        JSONObject confirm(JSONObject reviewed)throws Exception{return input("confirmInputs",new JSONObject().put("digest",reviewed.getJSONObject("input").getJSONObject("review").getString("digest")).put("billingAcknowledged",true));}
        JSONObject settle()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);JSONObject value=null;while(System.nanoTime()<until){value=action("query",new JSONObject().put("taskId",child));JSONObject job=value.optJSONObject("originalJob");if(job!=null&&job.optString("status").matches("completed|failed|blocked|cancelled|unknown"))return value;Thread.sleep(10);}throw new AssertionError("Original group job did not settle: "+value+Files.readString(root.resolve("server-stderr.log")));}
        JSONObject resultReview()throws Exception{JSONObject contexts=action("describe",new JSONObject());JSONArray replies=contexts.getJSONArray("replyContexts");assertTrue(contexts.toString(),replies.length()>0);return action("reviewResult",new JSONObject().put("taskId",child).put("replyEventId",replies.getJSONObject(0).getString("eventId")));}
        JSONObject sendResult(JSONObject review)throws Exception{return action("confirmResult",new JSONObject().put("digest",review.getJSONObject("returnReview").getString("digest")).put("contentAndAudienceAcknowledged",true));}
        int count(String name)throws Exception{return Files.exists(root.resolve(name))?Integer.parseInt(Files.readString(root.resolve(name))):0;}
        SQLiteDatabase database()throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return(SQLiteDatabase)field.get(tasks);}
        public void close()throws Exception{
            nativeGroup.close();if(remoteOwner!=null)remoteOwner.setForeground(activity.get(),false);if(groupHost!=null)groupHost.close();if(features!=null)features.close();if(groups!=null)groups.close();nativeQueue.shutdownNow();nativeQueue.awaitTermination(5,TimeUnit.SECONDS);if(api!=null)api.stop(0);
            if(tasks!=null){synchronized(tasks){database().close();}tasks=null;}if(companion!=null){companion.destroy();if(!companion.waitFor(6,TimeUnit.SECONDS)){companion.destroyForcibly();companion.waitFor(2,TimeUnit.SECONDS);}assertFalse(companion.isAlive());if(Files.exists(root.resolve("resource-release.json"))){JSONObject proof=new JSONObject(Files.readString(root.resolve("resource-release.json")));assertEquals(0,proof.getInt("activeProcesses"));assertEquals(0,proof.getInt("activeDrivers"));assertEquals(0,proof.getInt("resources"));assertTrue(proof.getBoolean("listenerClosed"));}}if(stdout!=null)stdout.close();activity.pause().stop().destroy();if(fault.get()!=null)throw new AssertionError("Controlled QQ peer",fault.get());
        }
    }
    @Test public void realGroupBytesUseOneReviewedApiModeJobAndSecondExactAudienceConfirmationReturnsOnlyGroupOwnedArtifact()throws Exception{
        try(Fixture f=new Fixture()){
            int privateCount=AttachmentRepository.get(f.app).book.list().length();JSONObject reviewed=f.review();assertEquals("EXTERNAL_GROUP",reviewed.getJSONObject("input").getString("inferenceAudience"));assertFalse(reviewed.getJSONObject("input").getBoolean("privateBookImported"));assertEquals(0,f.count("create-count"));assertEquals(0,f.uploads.get());f.confirm(reviewed);JSONObject settled=f.settle();assertEquals(settled.toString(),"completed",settled.getJSONObject("originalJob").getString("status"));assertArrayEquals(f.raw,Files.readAllBytes(f.root.resolve("protocol/actual-input.bin")));assertEquals(1,f.count("create-count"));assertEquals(1,f.count("upload-count"));assertEquals(0,f.uploads.get());assertEquals(0,f.replies.size());
            JSONObject child=f.tasks.get(f.child,false),source=f.tasks.get(f.parent,false);assertEquals("native_remote_group_inputs",child.getJSONObject("origin").getString("kind"));assertEquals(source.getString("actorId"),child.getString("actorId"));assertEquals(source.getString("spaceId"),child.getString("spaceId"));assertNotEquals("owner",child.getString("actorId"));assertEquals(f.parent,child.getString("parentTaskId"));assertNotEquals(f.child,child.getString("parentOperationId"));assertThrows(SecurityException.class,()->f.tasks.remoteOwnerTarget(f.child));
            synchronized(f.tasks){f.database().beginTransaction();try{assertThrows("An external group child cannot mint a private phone run",SecurityException.class,()->f.tasks.startAttempt(f.child,"background",5));}finally{f.database().endTransaction();}}
            JSONObject result=f.resultReview();String text=result.getJSONObject("returnReview").getString("text");assertEquals(0,f.uploads.get());JSONObject sent=f.sendResult(result);assertEquals("accepted",sent.getJSONObject("delivery").getString("state"));assertFalse(sent.getJSONObject("delivery").getString("platformMessageId").isEmpty());assertEquals("sent",f.channels.snapshot().getJSONArray("outbox").getJSONObject(0).getString("status"));assertEquals(1,f.uploads.get());assertEquals(1,f.replies.size());assertArrayEquals(text.getBytes(StandardCharsets.UTF_8),f.uploaded.get());assertEquals("source-message",f.replies.get(0).getString("msg_id"));assertEquals("actual-group-opaque-upload",f.replies.get(0).getJSONObject("media").getString("file_info"));assertEquals(privateCount,AttachmentRepository.get(f.app).book.list().length());JSONObject row=sent.getJSONObject("returnOperation");assertEquals("receipt",row.getString("state"));assertEquals(source.getString("spaceId"),row.getJSONObject("receipt").getJSONObject("artifact").getString("spaceId"));
            JSONObject second=f.resultReview();f.sendResult(second);assertEquals(1,f.uploads.get());assertEquals(1,f.replies.size());assertEquals(1,f.count("create-count"));
        }
    }
    @Test public void configuredOrNonApiAccountAndRevokedNativeWindowNeverBecomeGroupDispatchAuthority()throws Exception{
        try(Fixture f=new Fixture()){
            Files.writeString(f.root.resolve("protocol/mode.txt"),"chatgpt");assertThrows(Exception.class,f::review);assertEquals(0,f.count("create-count"));assertEquals(0,f.count("upload-count"));Files.delete(f.root.resolve("protocol/mode.txt"));JSONObject firstReview=f.review();String stalePage=f.page;f.nativeGroup.leaveRemoteInputs(f.window,f.page);assertThrows(SecurityException.class,()->f.confirm(firstReview));assertEquals(0,f.count("create-count"));
            f.page=f.owner("remoteInputs.openPage",new JSONObject().put("requestId",f.request)).getString("pageId");assertNotEquals(stalePage,f.page);final JSONObject accepted=f.review();f.group=f.command("member.set",new JSONObject().put("id",f.groupId).put("revision",f.group.getLong("revision")).put("senderId","source-member").put("displayName","Revoked original member").put("role","member").put("enabled",false));JSONObject refused=f.confirm(accepted);assertFalse(refused.toString(),refused.getJSONObject("input").getBoolean("registered"));assertNull(f.tasks.get(f.child,false));assertEquals(0,f.count("create-count"));assertEquals(0,f.count("upload-count"));assertEquals(0,f.uploads.get());
        }
    }
    @Test public void lostOriginalBinaryPutAckQueriesOneJobWithoutSecondBodyOrAutomaticWorkerStartAndCancelDoesNotClaimDeletion()throws Exception{
        try(Fixture f=new Fixture()){
            Files.writeString(f.root.resolve("drop-upload-ack"),"controlled lost ACK after original bytes persisted");JSONObject reviewed=f.review();JSONObject unknown=f.confirm(reviewed);assertEquals(1,f.count("create-count"));assertEquals(1,f.count("upload-count"));assertFalse(Files.exists(f.root.resolve("protocol/starts")));assertTrue(unknown.getJSONObject("input").getBoolean("registered"));JSONObject queried=f.input("queryInputs",new JSONObject());assertEquals("ready",queried.getJSONObject("input").getJSONObject("inputState").getString("phase"));assertEquals(1,f.count("upload-count"));assertThrows(SecurityException.class,()->f.confirm(reviewed));assertFalse(Files.exists(f.root.resolve("protocol/starts")));f.action("cancel",new JSONObject().put("taskId",f.child));JSONObject cancelled=f.action("query",new JSONObject().put("taskId",f.child)).getJSONObject("originalJob");assertEquals("cancelled",cancelled.getString("status"));assertTrue(cancelled.getJSONObject("inputState").getBoolean("copiesMayRemain"));assertFalse(cancelled.getJSONObject("inputState").getBoolean("cleanupConfirmed"));assertEquals(1,f.count("create-count"));assertEquals(1,f.count("upload-count"));assertEquals(0,f.uploads.get());
        }
    }
    @Test public void actualGroupFreezeThenSqlAckLossReconcilesOriginalArtifactAndUnconfirmedPlatformUploadNeverPostsOrRetries()throws Exception{
        try(Fixture f=new Fixture()){
            f.confirm(f.review());f.settle();JSONObject review=f.resultReview();String op=review.getJSONObject("returnReview").getJSONObject("scope").getString("operationId");synchronized(f.tasks){f.database().execSQL("CREATE TRIGGER lose_group_return_ack BEFORE UPDATE OF state ON operations WHEN OLD.tool='remote_group_return' AND NEW.state='receipt' BEGIN SELECT RAISE(ABORT,'controlled original return receipt ACK loss'); END");}
            assertThrows(Exception.class,()->f.sendResult(review));assertEquals("unknown",f.tasks.originalGroupRemoteReturn(f.child,op).getString("state"));long physical;try(var paths=Files.walk(f.root.resolve("groups/artifacts"))){physical=paths.filter(path->path.getFileName().toString().endsWith(".blob")).count();}assertEquals(2,physical);assertEquals(0,f.uploads.get());synchronized(f.tasks){f.database().execSQL("DROP TRIGGER lose_group_return_ack");}JSONObject recovered=f.action("queryReturn",new JSONObject().put("taskId",f.child).put("operationId",op));assertEquals("receipt",recovered.getJSONObject("returnOperation").getString("state"));try(var paths=Files.walk(f.root.resolve("groups/artifacts"))){assertEquals(physical,paths.filter(path->path.getFileName().toString().endsWith(".blob")).count());}String artifact=recovered.getJSONObject("returnOperation").getJSONObject("receipt").getJSONObject("artifact").getString("artifactId");
            f.dropUploadAck=true;JSONObject sent=f.sendResult(f.resultReview());assertEquals("unknown",sent.getJSONObject("delivery").getString("state"));assertEquals(1,f.uploads.get());assertEquals(0,f.replies.size());JSONObject again=f.sendResult(f.resultReview());assertEquals(artifact,again.getJSONObject("returnOperation").getJSONObject("receipt").getJSONObject("artifact").getString("artifactId"));assertEquals("unknown",again.getJSONObject("delivery").getString("state"));assertEquals(1,f.uploads.get());assertEquals(0,f.replies.size());assertEquals(1,f.count("create-count"));
        }
    }
}
