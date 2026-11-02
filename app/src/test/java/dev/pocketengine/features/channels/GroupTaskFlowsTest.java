package dev.pocketengine.features.channels;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.groups.GroupHostPort;
import dev.pocketengine.features.groups.GroupsModule;
import dev.pocketengine.features.groups.host.*;
import dev.pocketengine.media.MediaModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual signed QQ webhook, original SQLite/runner/media executor and loopback file/upload/message bytes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class GroupTaskFlowsTest {
    @Before public void keyFixture(){java.security.Security.addProvider(new java.security.Provider("GroupFlowKeyStore","1","Local protocol fixture"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void removeFixture(){java.security.Security.removeProvider("GroupFlowKeyStore");}
    interface ModelReply {JSONObject answer(JSONObject request)throws Exception;}
    static final class Fixture implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();final FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
        final HttpServer api=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final LinkedBlockingQueue<Runnable> work=new LinkedBlockingQueue<>(16);final ConcurrentHashMap<String,RunControl> controls=new ConcurrentHashMap<>();
        final AtomicInteger modelCalls=new AtomicInteger(),mediaCalls=new AtomicInteger(),uploads=new AtomicInteger(),downloads=new AtomicInteger();final CopyOnWriteArrayList<JSONObject> replies=new CopyOnWriteArrayList<>();
        byte[] attachment="ACTUAL GROUP FILE CONTENT".getBytes(StandardCharsets.UTF_8);final byte[] wave=new byte[48];String attachmentMime="text/plain";JSONObject incomingExtra;final String secret="native-qq-group-flow-secret";
        final String base;String profile,groupId;JSONObject group;TaskStore tasks;ChannelsModule channels;GroupsModule groups;GroupTaskHost host;GroupArtifactStore artifacts;GroupMediaService mediaService;MediaModule media;
        GroupHostPort.Request admittedRequest;GroupHostPort.Authority admittedExecution;
        ModelReply model=request->message("actual model answer");boolean uploadAck=true,invalidMedia;
        Fixture()throws Exception {
            System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII),0,wave,0,4);System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII),0,wave,8,4);
            api.createContext("/",exchange->{try {
                String path=exchange.getRequestURI().getPath();byte[] requestBytes=exchange.getRequestBody().readAllBytes();JSONObject request=requestBytes.length==0?new JSONObject():new JSONObject(new String(requestBytes,StandardCharsets.UTF_8)),response;byte[] bytes;
                if(path.equals("/v1/chat/completions")){modelCalls.incrementAndGet();JSONArray tools=request.optJSONArray("tools");String toolText=tools==null?"":tools.toString();assertFalse(toolText.contains("memory_"));assertFalse(toolText.contains("phone_"));assertFalse(toolText.contains("remote_"));assertFalse(toolText.contains("workspace_"));assertFalse(request.getJSONArray("messages").toString().contains("PRIVATE OWNER FILE SENTINEL"));JSONObject assistant=model.answer(request);response=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",assistant).put("finish_reason",assistant.optJSONArray("tool_calls")==null?"stop":"tool_calls")));bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");}
                else if(path.equals("/v1/audio/speech")){mediaCalls.incrementAndGet();assertEquals("wav",request.getString("response_format"));bytes=invalidMedia?"not a complete media receipt".getBytes(StandardCharsets.UTF_8):wave;exchange.getResponseHeaders().add("Content-Type","audio/wav");}
                else if(path.equals("/app/getAppAccessToken")){bytes=new JSONObject().put("access_token","real-fixture-token").put("expires_in",7200).toString().getBytes(StandardCharsets.UTF_8);}
                else if(path.equals("/v2/groups/room-a/files")){uploads.incrementAndGet();assertEquals("QQBot real-fixture-token",exchange.getRequestHeaders().getFirst("Authorization"));assertFalse(request.getBoolean("srv_send_msg"));assertEquals(3,request.getInt("file_type"));assertArrayEquals(wave,java.util.Base64.getDecoder().decode(request.getString("file_data")));bytes=(uploadAck?new JSONObject().put("file_info","actual-opaque-file-info"):new JSONObject().put("accepted",true)).toString().getBytes(StandardCharsets.UTF_8);}
                else if(path.equals("/v2/groups/room-a/messages")){assertEquals("QQBot real-fixture-token",exchange.getRequestHeaders().getFirst("Authorization"));replies.add(request);bytes=new JSONObject().put("id","platform-"+replies.size()).toString().getBytes(StandardCharsets.UTF_8);}
                else if(path.equals("/qq-attachment.txt")){downloads.incrementAndGet();assertNull(exchange.getRequestHeaders().getFirst("Authorization"));bytes=attachment;exchange.getResponseHeaders().add("Content-Type","text/plain");}
                else throw new AssertionError("unexpected local route "+path);
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}});api.start();base="http://127.0.0.1:"+api.getAddress().getPort();open();
            profile=channels.handle("profile.save",new JSONObject().put("name","Native group flow").put("provider","qq").put("appId","flow-bot").put("secret",secret).put("enabled",true)).getString("id");
            media.handle("configure",new JSONObject().put("baseUrl",base+"/v1").put("ttsModel","actual-fixture-tts").put("voice","fixture"));
            group=groups.ownerCommand("group.create",new JSONObject().put("name","Native group").put("provider","qq").put("accountId",profile).put("roomId","room-a"));groupId=group.getString("id");group=groups.ownerCommand("member.set",new JSONObject().put("id",groupId).put("revision",group.getLong("revision")).put("senderId","member-a").put("displayName","Member").put("role","member").put("enabled",true));
        }
        void open()throws Exception {
            tasks=new TaskStore(android);channels=new ChannelsModule(context,new ChannelHttp(base),new QqGatewayClient(true),new WeixinClient(),new GroupInboundFiles(true));media=new MediaModule(context);
            GroupChannelBridge bridge=channels.groupBridge();File directory=new File(android.getFilesDir(),"groups-flow");artifacts=new GroupArtifactStore(directory);mediaService=new GroupMediaService(media,AttachmentRepository.get(android).book,artifacts);
            ModelConfig config=new ModelConfig(base+"/v1","","group-flow-model",false,8,10,"openai_chat",256);
            host=new GroupTaskHost(tasks,command->{if(!work.offer(command))throw new java.util.concurrent.RejectedExecutionException();},controls,()->config,new GroupSafeCapabilities.Services(artifacts,null,mediaService),id->{},error->{});
            // Transparent test observer: only records the real native admission proof; all work still uses the original host.
            GroupHostPort observed=new GroupHostPort(){
                public TaskReceipt admit(Request request,Authority authority)throws Exception {TaskReceipt receipt=host.admit(request,authority);admittedRequest=request;admittedExecution=authority;return receipt;}
                public TaskReceipt inspect(Request request,Authority authority)throws Exception{return host.inspect(request,authority);}
                public Progress inspectProgress(Request request,Authority authority)throws Exception{return host.inspectProgress(request,authority);}
                public void checkInputSources(Request request,Authority read)throws Exception {var core=ChannelsModule.class.getDeclaredField("core");core.setAccessible(true);assertFalse("ChannelCore monitor must never enter the original SQL source check",Thread.holdsLock(core.get(channels)));host.checkInputSources(request,read);}
                public TaskReceipt input(Request request,dev.pocketengine.features.groups.GroupTaskInput input,Authority authority)throws Exception{return host.input(request,input,authority);}
                public TaskReceipt resume(Request request,Authority authority)throws Exception{return host.resume(request,authority);}
                public TaskReceipt recover(Request request,String run,int epoch,long revision,Authority authority)throws Exception{return host.recover(request,run,epoch,revision,authority);}
                public TaskReceipt cancel(Request request,String run,int epoch,Authority authority)throws Exception{return host.cancel(request,run,epoch,authority);}
            };
            groups=new GroupsModule(directory,bridge,observed,bridge,()->{});bridge.bind(groups,command->{if(!work.offer(command))throw new java.util.concurrent.RejectedExecutionException();});bridge.bindArtifacts(artifacts);
            host.setResultSink((request,receipt)->{if(receipt.status.equals("completed"))for(GroupHostPort.Output output:receipt.outputs)groups.sendOutput(request.requestId,output.outputId);});channels.handle("server.start",new JSONObject().put("port",0));
        }
        void enable(String capability)throws Exception {JSONObject quota=group.getJSONObject("quota");quota.put("dailyTokens",200000).put("maxTokensPerTask",60000);group=groups.ownerCommand("policy.set",new JSONObject().put("id",groupId).put("revision",group.getLong("revision")).put("enabled",true).put("visibility","requester").put("quota",quota).put("grants",new JSONArray().put(new JSONObject().put("capability",capability).put("resource",capability.startsWith("public.")?"public":capability.equals("group.files")?group.getString("spaceId"):mediaService.currentResource()).put("expiresAt",System.currentTimeMillis()+3_600_000).put("allowGroupAudience",true))));}
        void send(String command,boolean file)throws Exception {send(command,file,true);}
        void send(String command,boolean file,boolean runQueued)throws Exception {
            send(command,file,runQueued,"source-message");
        }
        void send(String command,boolean file,boolean runQueued,String messageId)throws Exception {
            JSONObject data=new JSONObject().put("id",messageId).put("group_openid","room-a").put("content",command).put("timestamp",Instant.now().toString()).put("author",new JSONObject().put("member_openid","member-a"));
            if(file)data.put("attachments",new JSONArray().put(new JSONObject().put("url",base+"/qq-attachment.txt").put("content_type",attachmentMime).put("size",attachment.length)));
            if(incomingExtra!=null)for(java.util.Iterator<String> keys=incomingExtra.keys();keys.hasNext();){String key=keys.next();data.put(key,incomingExtra.get(key));}
            byte[] body=new JSONObject().put("op",0).put("t","GROUP_AT_MESSAGE_CREATE").put("d",data).toString().getBytes(StandardCharsets.UTF_8);String timestamp=String.valueOf(System.currentTimeMillis()/1000);byte[] prefix=timestamp.getBytes(StandardCharsets.UTF_8),signed=new byte[prefix.length+body.length];System.arraycopy(prefix,0,signed,0,prefix.length);System.arraycopy(body,0,signed,prefix.length,body.length);
            int port=channels.snapshot().getJSONObject("server").getInt("port");HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/events/"+profile).openConnection();connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setRequestProperty("x-bot-appid","flow-bot");connection.setRequestProperty("x-signature-timestamp",timestamp);connection.setRequestProperty("x-signature-ed25519",ChannelProtocol.hex(ChannelProtocol.sign(secret,signed)));connection.setFixedLengthStreamingMode(body.length);try(java.io.OutputStream output=connection.getOutputStream()){output.write(body);}int status=connection.getResponseCode();String rawResponse=new String((status==200?connection.getInputStream():connection.getErrorStream()).readAllBytes(),StandardCharsets.UTF_8);assertEquals(rawResponse,200,status);connection.disconnect();if(runQueued)drain();
        }
        void drain(){int count=0;Runnable next;while((next=work.poll())!=null){if(++count>20)throw new AssertionError("unbounded host worker loop");next.run();}}
        JSONObject task()throws Exception {assertEquals(tasks.list().toString(),1,tasks.list().length());return tasks.get(tasks.list().getJSONObject(0).getString("id"),false);}
        void restart()throws Exception {groups.close();host.close();channels.close();media.close();closeTasks();open();}
        // Test owns this original DB; TaskStore intentionally has no application close API.
        private void closeTasks(){if(tasks==null)return;try{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);synchronized(tasks){((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}}catch(ReflectiveOperationException failed){throw new AssertionError("original fixture DB cleanup failed",failed);}}
        @Override public void close(){if(groups!=null)groups.close();if(host!=null)host.close();if(channels!=null)channels.close();if(media!=null)media.close();closeTasks();api.stop(0);}
    }
    static JSONObject message(String text)throws Exception{return new JSONObject().put("role","assistant").put("content",text);}
    static JSONObject tool(String name,String args)throws Exception {return new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","call-"+java.util.UUID.randomUUID()).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args))));}
    @Test public void actualAuthenticatedGroupFileIsImportedAndReadWithoutPrivateAttachments()throws Exception {
        try(Fixture f=new Fixture()){
            f.enable("group.files");AttachmentRepository.get(f.android).book.importBytes(new ByteArrayInputStream("PRIVATE OWNER FILE SENTINEL".getBytes(StandardCharsets.UTF_8)),"private.txt","text/plain",new JSONObject());
            f.model=request->{int call=f.modelCalls.get();if(call==1)return tool("group_files_list","{}");if(call==2){JSONArray messages=request.getJSONArray("messages");JSONObject last=messages.getJSONObject(messages.length()-1);JSONObject receipt=new JSONObject(last.getString("content"));String id=receipt.getJSONArray("artifacts").getJSONObject(0).getString("artifactId");return tool("group_file_read",new JSONObject().put("artifactId",id).toString());}assertTrue(request.getJSONArray("messages").toString().contains("ACTUAL GROUP FILE CONTENT"));return message("Actual group file content was read.");};
            f.send("/file --tokens=60000 Summarize the actual group attachment",true);JSONObject task=f.task();assertEquals(task.toString(),"completed",task.getString("status"));assertEquals(3,f.modelCalls.get());assertEquals(1,f.downloads.get());assertEquals(1,task.getJSONArray("groupInputArtifacts").length());assertEquals(f.group.getString("spaceId"),task.getJSONArray("groupInputArtifacts").getJSONObject(0).getString("spaceId"));assertEquals(1,f.replies.size());assertEquals(0,task.getJSONArray("operations").length());
        }
    }
    @Test public void groupMediaApprovalResumeAfterRestartUsesSameTaskAndRealTwoPhaseOutbox()throws Exception {
        try(Fixture f=new Fixture()){
            f.enable("media.audio");f.model=request->f.modelCalls.get()<3?tool("group_media_audio","{\"prompt\":\"Read this public group greeting\"}"):message("Actual group audio produced.");
            f.send("/audio --tokens=60000 Create a public greeting",false);JSONObject first=f.task();assertEquals(first.toString(),"awaiting_user",first.getString("status"));assertEquals(0,f.mediaCalls.get());assertEquals(0,f.uploads.get());assertEquals(0,f.replies.size());String id=first.getString("id"),requestId=first.getJSONObject("origin").getString("requestId");long spent=first.getLong("groupReservedTokens");
            f.tasks.decideCapabilityApproval(id,first.getJSONArray("approvals").getJSONObject(0).getString("id"),true);f.restart();f.groups.ownerCommand("task.resume",new JSONObject().put("requestId",requestId));f.drain();JSONObject resumed=f.task();assertEquals(id,resumed.getString("id"));assertEquals(resumed.toString(),"needs_confirmation",resumed.getString("status"));assertEquals(2,resumed.getInt("coordinatorEpoch"));assertEquals(1,f.mediaCalls.get());assertEquals(3,f.modelCalls.get());assertTrue(resumed.getLong("groupReservedTokens")>spent);assertEquals(2,resumed.getJSONArray("operations").length());assertEquals(0,f.uploads.get());
            f.tasks.status(id,"completed","Native owner verified actual group audio");f.host.publishTaskState(id);f.drain();assertEquals(1,f.uploads.get());assertEquals(2,f.replies.size());JSONObject rich=f.replies.get(1);assertEquals(7,rich.getInt("msg_type"));assertEquals("source-message",rich.getString("msg_id"));assertEquals("actual-opaque-file-info",rich.getJSONObject("media").getString("file_info"));
            JSONObject receipt=f.groups.ownerCommand("task.inspect",new JSONObject().put("requestId",requestId)).getJSONObject("task");assertEquals(2,receipt.getJSONArray("outputs").length());String audio=receipt.getJSONArray("outputs").getJSONObject(1).getString("artifactId");assertEquals("audio/wav",receipt.getJSONArray("outputs").getJSONObject(1).getString("mimeType"));assertFalse(audio.isEmpty());f.host.publishTaskState(id);f.drain();assertEquals(1,f.uploads.get());assertEquals(2,f.replies.size());assertThrows(SecurityException.class,()->f.groups.ownerCommand("task.resume",new JSONObject().put("requestId",requestId)));
        }
    }
    @Test public void unknownMediaGenerationCannotResumeOrCreateAnotherProviderRequest()throws Exception {
        try(Fixture f=new Fixture()){
            f.enable("media.audio");f.invalidMedia=true;f.model=request->tool("group_media_audio","{\"prompt\":\"One bounded public greeting\"}");f.send("/audio --tokens=60000 Create one greeting",false);JSONObject first=f.task();f.tasks.decideCapabilityApproval(first.getString("id"),first.getJSONArray("approvals").getJSONObject(0).getString("id"),true);String request=first.getJSONObject("origin").getString("requestId");f.groups.ownerCommand("task.resume",new JSONObject().put("requestId",request));f.drain();assertEquals(1,f.mediaCalls.get());assertEquals("reconciling",f.task().getString("status"));assertEquals(0,f.uploads.get());assertThrows(SecurityException.class,()->f.groups.ownerCommand("task.resume",new JSONObject().put("requestId",request)));assertEquals(1,f.mediaCalls.get());assertEquals(0,f.replies.size());
        }
    }
    @Test public void defaultBudgetReallyExecutesAPlainTaskRatherThanFailingBeforeModel()throws Exception {
        try(Fixture f=new Fixture()){f.enable("public.answer");f.send("/task Explain a public fact",false);assertEquals(f.task().toString(),"completed",f.task().getString("status"));assertEquals(1,f.modelCalls.get());assertEquals(1,f.replies.size());assertTrue(f.task().getLong("groupReservedTokens")<=4000);}
    }
    @Test public void noFileGrantCannotTriggerAttachmentDownloadOrModel()throws Exception {
        try(Fixture f=new Fixture()){f.enable("public.answer");f.send("/file --tokens=60000 Read an attachment without a file grant",true);assertEquals(0,f.downloads.get());assertEquals(0,f.modelCalls.get());assertEquals(0,f.tasks.list().length());assertEquals(0,f.replies.size());}
    }
    @Test public void guessedPrivateAttachmentIdCannotBeReadByTheGroupExecutor()throws Exception {
        try(Fixture f=new Fixture()){f.enable("group.files");JSONObject privateFile=AttachmentRepository.get(f.android).book.importBytes(new ByteArrayInputStream("PRIVATE OWNER FILE SENTINEL".getBytes(StandardCharsets.UTF_8)),"private.txt","text/plain",new JSONObject());f.model=request->tool("group_file_read",new JSONObject().put("artifactId",privateFile.getString("id")).toString());f.send("/file --tokens=60000 Try only the attached group file",true);assertNotEquals("completed",f.task().getString("status"));assertEquals(1,f.modelCalls.get());assertEquals(0,f.replies.size());assertFalse(f.task().toString().contains("PRIVATE OWNER FILE SENTINEL"));}
    }
    @Test public void revocationAfterNativeAdmissionStopsQueuedRunnerBeforeAnyModelWrite()throws Exception {
        try(Fixture f=new Fixture()){f.enable("public.answer");f.send("/task Explain one public fact",false,false);Runnable ingress=f.work.poll();assertNotNull(ingress);ingress.run();assertEquals(1,f.tasks.list().length());
            f.group=f.groups.ownerCommand("policy.set",new JSONObject().put("id",f.groupId).put("revision",f.group.getLong("revision")).put("enabled",false).put("visibility","requester").put("quota",f.group.getJSONObject("quota")).put("grants",f.group.getJSONArray("grants")));f.drain();assertEquals(0,f.modelCalls.get());assertEquals(0,f.replies.size());assertEquals("cancelled",f.task().getString("status"));
            JSONObject nativeRead=f.groups.ownerCommand("task.inspect",new JSONObject().put("requestId",f.task().getJSONObject("origin").getString("requestId")));assertEquals("cancelled",nativeRead.getJSONObject("task").getString("status"));
        }
    }
    @Test public void missingUploadAckNeverRetriesUploadOrPostsRichMediaMessage()throws Exception {
        try(Fixture f=new Fixture()){f.enable("media.audio");f.uploadAck=false;f.model=request->f.modelCalls.get()<3?tool("group_media_audio","{\"prompt\":\"One exact public greeting\"}"):message("Real audio was generated.");f.send("/audio --tokens=60000 Generate one greeting",false);JSONObject first=f.task();f.tasks.decideCapabilityApproval(first.getString("id"),first.getJSONArray("approvals").getJSONObject(0).getString("id"),true);String request=first.getJSONObject("origin").getString("requestId");f.groups.ownerCommand("task.resume",new JSONObject().put("requestId",request));f.drain();String id=f.task().getString("id");assertEquals(1,f.mediaCalls.get());f.tasks.status(id,"completed","Native verified original audio");f.host.publishTaskState(id);f.drain();assertEquals(1,f.uploads.get());assertEquals(1,f.replies.size());JSONObject state=f.groups.ownerCommand("task.inspect",new JSONObject().put("requestId",request)).getJSONObject("task");String audio=state.getJSONArray("outputs").getJSONObject(1).getString("outputId");assertEquals("unknown",f.groups.sendOutput(request,audio).getString("state"));assertEquals(1,f.uploads.get());assertEquals(1,f.replies.size());}
    }

    @Test public void unavailableRealServiceCreatesAFailedOriginalTaskAndReleasesConcurrency()throws Exception {
        try(Fixture f=new Fixture()){f.enable("public.search");f.send("/search --tokens=60000 Search public facts without a mounted search service",false);JSONObject task=f.task();assertEquals("failed",task.getString("status"));assertEquals("",task.optString("runId"));assertEquals(0,f.modelCalls.get());long deadline=System.nanoTime()+5_000_000_000L;while((f.replies.size()<1||!f.channels.snapshot().getJSONArray("outbox").getJSONObject(0).optString("status").equals("sent"))&&System.nanoTime()<deadline)Thread.sleep(10);assertEquals(1,f.replies.size());assertEquals("sent",f.channels.snapshot().getJSONArray("outbox").getJSONObject(0).getString("status"));assertEquals("group_capability_failed",f.channels.snapshot().getJSONArray("outbox").getJSONObject(0).getString("nativeControlCode"));assertEquals(0,f.groups.ownerSnapshot().getJSONArray("groups").getJSONObject(0).getJSONObject("usage").getInt("activeTasks"));}
    }

}
