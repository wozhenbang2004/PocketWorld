package dev.pocketengine.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.MainActivity;
import dev.pocketengine.media.MediaController;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.CompatibilityChatHandoff;
import dev.pocketengine.features.assistant.OwnerComposerStore;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=ReadyApplicationFixture.NoUnrelatedStartup.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class CompatibilityChatHandoffTest {
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("CompatibilityKeyStore","1","Test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    interface Check{boolean done()throws Exception;}
    static void await(Check condition)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);while(!condition.done()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(condition.done());}
    static final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final ComposeActivity activity=Robolectric.buildActivity(ComposeActivity.class).get();
        final PocketWorldController controller;
        final MediaController media;
        Fixture()throws Exception{ReadyApplicationFixture.initializeHost(app);media=new MediaController(activity,app::publish);controller=new PocketWorldController(activity,app);set("media",media);set("controller",controller);set("businessMounted",true);set("businessGeneration",app.bootstrapState().getLong("generation"));var foreground=PocketWorldController.class.getDeclaredField("voiceForeground");foreground.setAccessible(true);foreground.setBoolean(controller,true);controller.attach();await(()->controller.getComposerDraft()!=null);}
        void set(String name,Object value)throws Exception{var f=ComposeActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
        String conversation()throws Exception{String id=UUID.randomUUID().toString();var repo=app.tasks.conversationRepository();repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId",id).put("title","原私聊").put("createdAt",1L));return null;});return id;}
        Intent open(String conversation,String text)throws Exception{return CompatibilityChatHandoff.open(app,"fixture-page",new JSONObject().put("conversationId",conversation).put("draft",text));}
        void deliver(Intent intent)throws Exception{var m=ComposeActivity.class.getDeclaredMethod("onNewIntent",Intent.class);m.setAccessible(true);m.invoke(activity,intent);}
        void edit(String text){var d=controller.getComposerDraft();controller.editComposerText(d.key,controller.getComposerViewEpoch(),text);}
        @Override public void close()throws Exception{try{controller.detach();media.close();}finally{ReadyApplicationFixture.closeHost(app);}}
    }
    @Test public void realNativeEntryTransfersExactConversationDraftOnceWithoutSending()throws Exception{
        try(Fixture f=new Fixture()){
            String original=f.conversation();Intent issued=f.open(original,"未发送的兼容原文");var legacy=Robolectric.buildActivity(MainActivity.class,new Intent(issued).setClass(f.app,MainActivity.class)).create();try{assertTrue(legacy.get().isFinishing());Intent intent=Shadows.shadowOf(legacy.get()).getNextStartedActivity();assertEquals(ComposeActivity.class.getName(),intent.getComponent().getClassName());assertEquals(1,intent.getExtras().size());assertFalse(intent.getExtras().toString().contains("未发送"));String id=intent.getStringExtra(CompatibilityChatHandoff.EXTRA);f.deliver(intent);await(()->CompatibilityChatHandoff.read(f.app,id).applied());assertEquals(original,f.controller.getState().getConversationId());assertEquals("未发送的兼容原文",f.controller.getComposerDraft().text);assertEquals(0,f.app.tasks.list().length());assertFalse(intent.hasExtra(CompatibilityChatHandoff.EXTRA));
                var stored=new OwnerComposerStore(f.app.getFilesDir()).load().get();assertEquals(original,stored.active().conversationId);assertEquals("未发送的兼容原文",stored.active().text);f.edit("原生继续编辑");f.controller.continueCompatibilityChat(id);assertEquals("原生继续编辑",f.controller.getComposerDraft().text);assertEquals(0,f.app.tasks.list().length());
            }finally{legacy.destroy();}
        }
    }
    @Test public void transferredDraftUsesExistingNativeSendAckAndFinalConversationPath()throws Exception{
        try(Fixture f=new Fixture()){
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);var bodies=new java.util.concurrent.CopyOnWriteArrayList<String>();server.createContext("/",exchange->{try{bodies.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));byte[] reply="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"NATIVE-HANDOFF-ANSWER\"},\"finish_reason\":\"stop\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);}finally{exchange.close();}});server.start();
            try{
                String endpoint="http://127.0.0.1:"+server.getAddress().getPort();f.app.modelConnections.addAndSelectConnection("Controlled handoff fixture",new JSONObject().put("baseUrl",endpoint).put("model","fixture").put("provider","openai_chat").put("maxSteps",1).put("sendScreenshots",false).put("clearApiKey",true).put("cleartextConsent",dev.pocketengine.core.ModelConfig.cleartextConsentFor("openai_chat",endpoint)));f.controller.refresh();String conversation=f.conversation();Intent intent=f.open(conversation,"EXACT-COMPATIBILITY-DRAFT");String token=intent.getStringExtra(CompatibilityChatHandoff.EXTRA);f.deliver(intent);await(()->CompatibilityChatHandoff.read(f.app,token).applied());assertEquals(0,bodies.size());assertEquals(0,f.app.tasks.list().length());
                var draft=f.controller.getComposerDraft();f.controller.sendChat(draft.text,draft.key,f.controller.getComposerViewEpoch());await(()->bodies.size()==1&&!f.controller.getState().getChatSubmitting()&&!f.controller.getState().getChatBusy()&&!f.controller.getState().getChatReceipts().isEmpty());JSONObject history=f.app.features.module("assistant").handle("get",new JSONObject().put("conversationId",conversation));assertTrue(history.toString().contains("NATIVE-HANDOFF-ANSWER"));assertTrue(bodies.get(0).contains("EXACT-COMPATIBILITY-DRAFT"));assertEquals(1,f.app.tasks.list().length());assertEquals(conversation,f.app.tasks.list().getJSONObject(0).getString("conversationId"));
            }finally{server.stop(0);}
        }
    }
    @Test public void existingDraftRequiresExplicitAppendAndOriginalRevision()throws Exception{
        try(Fixture f=new Fixture()){
            String original=f.conversation();f.controller.openConversation(original);await(()->original.equals(f.controller.getState().getConversationId()));f.edit("原生草稿");Intent intent=f.open(original,"兼容草稿");String id=intent.getStringExtra(CompatibilityChatHandoff.EXTRA);f.deliver(intent);AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertEquals("原生草稿",f.controller.getComposerDraft().text);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();await(()->CompatibilityChatHandoff.read(f.app,id).applied());assertEquals("原生草稿\n\n兼容草稿",f.controller.getComposerDraft().text);assertEquals(0,f.app.tasks.list().length());
            f.deliver(f.open(original,"新兼容草稿"));dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();f.edit("对话框打开后编辑的新文字");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals("对话框打开后编辑的新文字",f.controller.getComposerDraft().text);await(()->f.controller.getState().getError()!=null);assertTrue(f.controller.getState().getError().contains("原草稿已变化"));
        }
    }
    @Test public void anotherLiveConversationDefersWithoutMixingDraftsThenReturnsToExactTarget()throws Exception{
        try(Fixture f=new Fixture()){
            String a=f.conversation(),b=f.conversation();f.controller.openConversation(a);await(()->a.equals(f.controller.getState().getConversationId()));f.edit("A原生草稿");String key=f.controller.getComposerDraft().key;
            var receive=PocketWorldController.class.getDeclaredMethod("receive",JSONObject.class);receive.setAccessible(true);JSONObject task=f.app.tasks.startAttempt(f.app.tasks.createCapabilityTask("A原执行",a).getString("id"),"capabilities",4);String run=task.getString("runId");JSONObject turn=new JSONObject().put("conversationId",a).put("taskId",task.getString("id")).put("runId",run).put("epoch",task.getInt("coordinatorEpoch"));var assistant=f.app.features.module("assistant");var activeField=assistant.getClass().getDeclaredField("active");activeField.setAccessible(true);@SuppressWarnings("unchecked") var active=(java.util.concurrent.atomic.AtomicReference<RunControl>)activeField.get(assistant);active.set(new RunControl());var turnField=assistant.getClass().getDeclaredField("activeTurn");turnField.setAccessible(true);turnField.set(assistant,turn);
            // Deliberately leave the UI busy snapshot stale: handoff must inspect the real original host.
            Intent intent=f.open(b,"B兼容草稿");String token=intent.getStringExtra(CompatibilityChatHandoff.EXTRA);f.deliver(intent);assertEquals(key,f.controller.getComposerDraft().key);assertEquals("A原生草稿",f.controller.getComposerDraft().text);assertFalse(CompatibilityChatHandoff.read(f.app,token).applied());
            active.set(null);turnField.set(assistant,null);
            receive.invoke(f.controller,new JSONObject().put("type","feature_event").put("feature","assistant").put("payload",new JSONObject().put("phase","completed").put("conversationId",a).put("runId",run)));
            await(()->CompatibilityChatHandoff.read(f.app,token).applied());assertEquals(b,f.controller.getComposerDraft().conversationId);assertEquals("B兼容草稿",f.controller.getComposerDraft().text);f.controller.openConversation(a);assertEquals("A原生草稿",f.controller.getComposerDraft().text);assertEquals(1,f.app.tasks.list().length());
        }
    }
    @Test public void attachmentOnlyNativeDraftIsNotSilentlyCombinedWithIncomingText()throws Exception{
        try(Fixture f=new Fixture()){
            String conversation=f.conversation();f.controller.openConversation(conversation);await(()->conversation.equals(f.controller.getState().getConversationId()));var field=PocketWorldController.class.getDeclaredField("ownerComposer");field.setAccessible(true);var model=(dev.pocketengine.features.assistant.OwnerComposerState)field.get(f.controller);var draft=model.active();String attachment=UUID.randomUUID().toString();model.edit(draft.key,draft.revision,"","queue",new JSONArray().put(new JSONObject().put("id",attachment).put("sha256","a".repeat(64)).put("name","已有附件.txt").put("mimeType","text/plain").put("size",1)));
            f.deliver(f.open(conversation,"兼容文字"));AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertEquals("",model.active().text);assertEquals(attachment,model.active().attachments().getJSONObject(0).getString("id"));dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("",model.active().text);assertEquals(1,model.active().attachments().length());assertEquals(0,f.app.tasks.list().length());
        }
    }
    @Test public void privateHandoffCannotEnterGroupDomain()throws Exception{
        try(Fixture f=new Fixture()){
            String conversation=f.conversation();f.edit("原私人草稿");Intent intent=f.open(conversation,"兼容原文");f.controller.enterOwnerDomain("groups");f.deliver(intent);assertEquals("原私人草稿",f.controller.getComposerDraft().text);assertTrue(f.controller.getState().getError().contains("退出群或团队"));assertEquals(0,f.app.tasks.list().length());f.controller.leaveOwnerDomain("groups");
        }
    }
    @Test public void unknownTicketAndConversationAreRejectedAndOriginalStopRemainsExplicit()throws Exception{
        try(Fixture f=new Fixture()){
            f.edit("不能被外部Intent覆盖");f.deliver(new Intent().putExtra(CompatibilityChatHandoff.EXTRA,UUID.randomUUID().toString()));assertEquals("不能被外部Intent覆盖",f.controller.getComposerDraft().text);assertThrows(Exception.class,()->f.open(UUID.randomUUID().toString(),"wrong"));
            String conversation=f.conversation();JSONObject task=f.app.tasks.createCapabilityTask("原执行",conversation);String id=task.getString("id");task=f.app.tasks.startAttempt(id,"capabilities",4);RunControl original=new RunControl();original.bindHostRun(id,task.getString("runId"),task.getInt("coordinatorEpoch"));f.app.tasks.status(id,"running","执行中");f.app.tasks.authorizeTools(id,task.getString("runId"),new JSONArray().put("fixture"));var journal=f.app.tasks.journal(id,task.getString("runId"),task.getInt("coordinatorEpoch"),original);String operation=journal.beginOperation("fixture",new JSONObject());journal.finishOperation(operation,new JSONObject().put("status","unknown"));
            var field=EngineApplication.class.getDeclaredField("capabilityControls");field.setAccessible(true);@SuppressWarnings("unchecked") var controls=(java.util.Map<String,RunControl>)field.get(f.app);controls.put(id,original);
            JSONObject turn=new JSONObject().put("taskId",id).put("runId",task.getString("runId")).put("epoch",task.getInt("coordinatorEpoch"));Intent target=CompatibilityChatHandoff.open(f.app,"fixture-control",new JSONObject().put("conversationId",conversation).put("draft","").put("originalTurn",turn));f.deliver(target);assertEquals(id,f.controller.getState().getRequestedTaskId());assertFalse(original.isCancelled());f.controller.stopTask(id);await(original::isCancelled);controls.remove(id);assertEquals(1,f.app.tasks.list().length());assertTrue(f.app.tasks.hasUnresolved(id));f.app.tasks.resolveOperation(id,operation,"not_occurred");
            f.app.tasks.startAttempt(id,"capabilities",4);assertThrows(SecurityException.class,()->CompatibilityChatHandoff.open(f.app,"fixture-control",new JSONObject().put("conversationId",conversation).put("draft","").put("originalTurn",turn)));
        }
    }
}
