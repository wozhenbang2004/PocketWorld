package dev.pocketengine.features.channels;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import androidx.activity.ComponentActivity;
import dev.pocketengine.features.NativeGroupOwnerWorkflows;
import dev.pocketengine.features.groups.GroupsModule;
import dev.pocketengine.features.groups.host.*;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.core.RunControl;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Native original completion SQL → exact foreground owner target/text review → actual original HTTP outbox. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class CompletionOutboxJourneyTest {
    public static class OwnerActivity extends ComponentActivity {}
    @Before public void vault(){java.security.Security.addProvider(new java.security.Provider("CompletionFixtureVault","1","Synthetic owner-keystore SPI"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void release(){java.security.Security.removeProvider("CompletionFixtureVault");}
    private interface Checked<T>{T run()throws Exception;}
    private static final class Fixture implements AutoCloseable {
        final QqOwnerImageJourneyTest.Fixture peer=new QqOwnerImageJourneyTest.Fixture();
        final ActivityController<OwnerActivity> activity=Robolectric.buildActivity(OwnerActivity.class).setup();
        final ExecutorService ownerQueue=Executors.newSingleThreadExecutor(),workers=Executors.newFixedThreadPool(2);final ConcurrentHashMap<String,RunControl> controls=new ConcurrentHashMap<>();
        NativeGroupOwnerWorkflows owner;TaskStore tasks;GroupsModule groups;GroupTaskHost host;ChannelsModule channel;String window,task,run,eventId;int epoch;final AtomicInteger actualPosts=new AtomicInteger();final java.util.List<JSONObject> sent=new CopyOnWriteArrayList<>();final AtomicReference<Throwable> fault=new AtomicReference<>();volatile boolean dropAck;
        Fixture(boolean verified)throws Exception {
            peer.bind();JSONObject received=peer.receive("completion-target","owner","本机主人已认证的原接收目标",null);eventId=received.getString("id");channel=peer.module;openOwner();
            task=tasks.create("已批准的原任务，不为投递重跑").getString("id");JSONObject started=tasks.startAttempt(task,"main_screen",4);run=started.getString("runId");epoch=started.getInt("coordinatorEpoch");tasks.endAttempt(task,run,"needs_confirmation","原任务实际摘要：17个零件，未附私人文件",new JSONObject().put("automaticallyVerified",false));if(verified)tasks.event(task,new JSONObject().put("type","user_verification").put("completed",true));tasks.status(task,"completed",verified?"主人已确认":"只有状态，不是核验");
            peer.server.createContext("/v2/users/owner/messages",exchange->{try{actualPosts.incrementAndGet();JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));sent.add(body);assertEquals("QQBot fixture-qq-token",exchange.getRequestHeaders().getFirst("Authorization"));assertEquals("completion-target",body.getString("msg_id"));assertEquals(0,body.getInt("msg_type"));assertTrue(body.getString("content").contains("17个零件"));assertFalse(body.has("media"));if(!dropAck){byte[] response="{\"id\":\"accepted-original-completion\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}}catch(Throwable problem){fault.compareAndSet(null,problem);}finally{exchange.close();}});
        }
        void openOwner()throws Exception {tasks=new TaskStore(peer.android);owner=new NativeGroupOwnerWorkflows(RuntimeEnvironment.getApplication(),OwnerActivity.class);java.io.File root=new java.io.File(peer.android.getFilesDir(),"completion-groups");host=new GroupTaskHost(tasks,workers,controls,peer.context.settings::get,new GroupSafeCapabilities.Services(new GroupArtifactStore(root),null,null),ignored->{},ignored->{});groups=new GroupsModule(root,null,host,null,owner);owner.bind(groups);owner.bindMaterials(host,tasks,channel,peer.context.settings::get);window=owner.open(activity.get());}
        SQLiteDatabase db()throws Exception {var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return(SQLiteDatabase)f.get(tasks);}
        <T>T work(Checked<T> action)throws Exception {try{return ownerQueue.submit(action::run).get(10,TimeUnit.SECONDS);}catch(ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw(Exception)cause;throw new AssertionError(cause);}}
        JSONObject call(String command,JSONObject input)throws Exception{return work(()->owner.call(window,()->owner.completions(command,input)));}
        JSONObject review()throws Exception{return call("completion.review",new JSONObject().put("taskId",task).put("eventId",eventId));}
        JSONObject confirm(JSONObject review)throws Exception{return call("completion.confirm",new JSONObject().put("digest",review.getString("digest")));}
        JSONObject send(JSONObject review)throws Exception{return call("completion.send",new JSONObject().put("digest",review.getString("digest")));}
        void cold()throws Exception {owner.close();host.close();groups.close();synchronized(tasks){db().close();}channel.close();channel=new ChannelsModule(peer.context,new ChannelHttp(peer.base),new QqGatewayClient(true),new WeixinClient(peer.base),new GroupInboundFiles(true));openOwner();}
        public void close()throws Exception {owner.close();host.close();groups.close();channel.close();ownerQueue.shutdownNow();workers.shutdownNow();assertTrue(workers.awaitTermination(4,TimeUnit.SECONDS));synchronized(tasks){db().close();}peer.close();activity.pause().stop().destroy();if(fault.get()!=null)throw new AssertionError(fault.get());}
    }
    @Test public void originalVerifiedTaskNeedsExactNativeTargetAndTextConfirmationBeforeOneActualOriginalPlatformDelivery()throws Exception {
        try(Fixture f=new Fixture(true)){
            JSONObject review=f.review(),scope=review.getJSONObject("review");String fact=scope.getString("factId");assertEquals(0,f.actualPosts.get());assertEquals(0,f.channel.snapshot().getJSONArray("outbox").length());assertFalse(scope.getBoolean("attachmentBytesIncluded"));assertEquals(f.task,scope.getString("taskId"));assertEquals(f.run,scope.getString("runId"));assertEquals("owner_verification",scope.getJSONObject("completionBasis").getString("kind"));assertEquals("qq_c2c",scope.getJSONObject("target").getString("conversationKind"));assertFalse(scope.getBoolean("businessSuccessIndependentlyVerified"));
            assertThrows(SecurityException.class,()->f.call("completion.send",new JSONObject().put("digest",review.getString("digest"))));JSONObject queued=f.confirm(review);assertEquals("queued",queued.getString("status"));assertEquals(0,f.actualPosts.get());assertEquals(queued.getString("id"),f.tasks.completionOutboxLink(fact).getString("outboxId"));assertEquals(queued.getString("id"),f.confirm(review).getString("id"));assertEquals(1,f.channel.snapshot().getJSONArray("outbox").length());JSONObject accepted=f.send(review);assertEquals("sent",accepted.getString("status"));assertEquals("accepted-original-completion",accepted.getString("receiptId"));assertEquals(1,f.actualPosts.get());assertEquals(scope.getString("text"),f.sent.get(0).getString("content"));assertEquals(0,f.peer.modelCalls.get());assertEquals(0,f.tasks.get(f.task,false).getJSONArray("operations").length());assertThrows(IllegalStateException.class,()->f.send(review));assertEquals(1,f.actualPosts.get());
            f.cold();JSONObject restored=f.call("completion.inspect",new JSONObject().put("factId",fact));assertEquals("sent",restored.getJSONObject("delivery").getString("status"));assertEquals(queued.getString("id"),restored.getJSONObject("link").getString("outboxId"));assertEquals(f.run,f.tasks.get(f.task,false).getString("runId"));assertEquals(1,f.actualPosts.get());
        }
    }
    @Test public void originalOutboxCommitThenSqlLinkAckFailureColdRecoversOnlyActualRecordAndNeverDispatchesBeforeAssociation()throws Exception {
        try(Fixture f=new Fixture(true)){
            JSONObject reviewed=f.review(),scope=reviewed.getJSONObject("review");String fact=scope.getString("factId");f.db().execSQL("CREATE TEMP TRIGGER completion_outbox_fixture_abort BEFORE INSERT ON meta WHEN NEW.key LIKE 'completion.outbox.%' BEGIN SELECT RAISE(ABORT,'fixture link ACK refused'); END");try{assertThrows(Exception.class,()->f.confirm(reviewed));assertNull(f.tasks.completionOutboxLink(fact));assertEquals(1,f.channel.snapshot().getJSONArray("outbox").length());assertThrows(SecurityException.class,()->f.channel.handle("outbox.send",new JSONObject().put("id",scope.getString("outboxId"))));assertEquals(0,f.actualPosts.get());}finally{f.db().execSQL("DROP TRIGGER completion_outbox_fixture_abort");}
            f.cold();JSONObject original=f.call("completion.inspect",new JSONObject().put("factId",fact));assertTrue(original.getBoolean("associationPending"));assertEquals(scope.getString("outboxId"),original.getJSONObject("delivery").getString("id"));assertNull(f.tasks.completionOutboxLink(fact));JSONObject recovered=f.call("completion.recoverLink",new JSONObject().put("factId",fact));assertTrue(recovered.getBoolean("associationRecovered"));assertFalse(recovered.getBoolean("messageSent"));assertEquals(scope.getString("outboxId"),f.tasks.completionOutboxLink(fact).getString("outboxId"));assertEquals(0,f.actualPosts.get());JSONObject current=f.review();assertEquals(scope.getString("outboxId"),f.confirm(current).getString("id"));assertEquals("sent",f.send(current).getString("status"));assertEquals(1,f.actualPosts.get());assertEquals(1,f.channel.snapshot().getJSONArray("outbox").length());assertEquals(f.run,f.tasks.get(f.task,false).getString("runId"));
        }
    }
    @Test public void lostActualDeliveryAckRemainsOneOriginalUnknownAfterColdReadAndCannotRequeueOrRerunBusiness()throws Exception {
        try(Fixture f=new Fixture(true)){
            f.dropAck=true;JSONObject review=f.review(),queued=f.confirm(review);String fact=review.getJSONObject("review").getString("factId");assertEquals("unknown",f.send(review).getString("status"));assertEquals(1,f.actualPosts.get());f.cold();JSONObject restored=f.call("completion.inspect",new JSONObject().put("factId",fact));assertEquals("unknown",restored.getJSONObject("delivery").getString("status"));assertEquals(queued.getString("id"),restored.getJSONObject("link").getString("outboxId"));assertThrows(IllegalStateException.class,()->f.channel.handle("outbox.send",new JSONObject().put("id",queued.getString("id"))));assertThrows(SecurityException.class,()->f.channel.handle("outbox.resolve",new JSONObject().put("id",queued.getString("id")).put("outcome","not_delivered").put("confirmed",true).put("note","未看到消息不是未发生证明")));assertEquals(1,f.actualPosts.get());assertEquals(f.run,f.tasks.get(f.task,false).getString("runId"));assertEquals(0,f.tasks.get(f.task,false).getJSONArray("operations").length());
        }
    }
    @Test public void completedLabelAndStaleForegroundRecipientOrForgottenOriginalCompletionCannotAuthorizeAnyHttpSend()throws Exception {
        try(Fixture f=new Fixture(false)){assertThrows(SecurityException.class,f::review);assertEquals(0,f.actualPosts.get());assertEquals(0,f.channel.snapshot().getJSONArray("outbox").length());}
        try(Fixture f=new Fixture(true)){
            JSONObject old=f.review();f.owner.leaveCompletions(f.window);assertThrows(SecurityException.class,()->f.confirm(old));assertEquals(0,f.channel.snapshot().getJSONArray("outbox").length());JSONObject review=f.review();JSONObject queued=f.confirm(review);JSONObject proof=f.tasks.originalCompletionFact(review.getJSONObject("review").getString("factId")).metadata();long version=proof.getJSONObject("completionBasis").getLong("eventSeq");f.tasks.forgetMemorySourceSnapshot(new dev.pocketengine.features.memory.SourceRef("task_evidence","task:"+f.task+":verified_result",version,"owner","private"));assertThrows(SecurityException.class,()->f.channel.handle("outbox.send",new JSONObject().put("id",queued.getString("id"))));assertEquals(0,f.actualPosts.get());
        }
        try(Fixture f=new Fixture(true)){JSONObject review=f.review(),queued=f.confirm(review);f.channel.handle("owner.unbind",new JSONObject().put("profileId","bot"));assertThrows(SecurityException.class,()->f.channel.handle("outbox.send",new JSONObject().put("id",queued.getString("id"))));assertEquals(0,f.actualPosts.get());}
    }
}
