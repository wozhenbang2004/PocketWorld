package dev.pocketengine.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.media.MediaController;
import dev.pocketengine.testutil.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSpeechRecognizer;
import org.robolectric.shadows.ShadowTextToSpeech;
import static org.junit.Assert.*;

/** Actual Activity/Controller/ASR API/native admission/SQLite/Runner. Audio and model HTTP are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class VoiceDraftMountedApplicationTest {
    private EngineApplication app;private ActivityController<ComposeActivity> activity;private PocketWorldController controller;private MediaController media;private HttpServer server;
    private final AtomicInteger models=new AtomicInteger();
    private static Object get(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private interface Check{boolean done()throws Exception;}
    private static void until(Check check)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!check.done()&&System.nanoTime()<end){idle();Thread.sleep(5);}idle();assertTrue("original voice input journey did not settle",check.done());}
    @Before public void setup()throws Exception{
        java.security.Security.addProvider(new java.security.Provider("VoiceDraftKeyStore","1","Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});
        app=(EngineApplication)RuntimeEnvironment.getApplication();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();int call=models.incrementAndGet();JSONObject message=new JSONObject().put("role","assistant");
            if(call<=2)message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","draft-workspace-"+call).put("type","function").put("function",new JSONObject().put("name","workspace_create").put("arguments",new JSONObject().put("name","听写编辑后确认的实际项目").toString()))));
            else message.put("content","已按你核对后的原输入创建项目，请查看原任务结果。");
            byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();app.settings.save(new JSONObject().put("provider","openai_chat").put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("model","draft-native-fixture").put("clearApiKey",true).put("sendScreenshots",false).put("maxSteps",8));
        activity=Robolectric.buildActivity(ComposeActivity.class).create().start().resume();controller=(PocketWorldController)get(activity.get(),"controller");media=(MediaController)get(activity.get(),"media");idle();
    }
    private void service(){ResolveInfo info=new ResolveInfo();info.serviceInfo=new ServiceInfo();info.serviceInfo.packageName="fixture.system.speech";info.serviceInfo.name="Recognition";Shadows.shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(RecognitionService.SERVICE_INTERFACE),info);}
    private SpeechRecognizer listen()throws Exception{service();Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);controller.startSystemVoiceInput(false);idle();assertEquals("listening",controller.getState().getVoiceInput().getString("phase"));return ShadowSpeechRecognizer.getLatestSpeechRecognizer();}
    private void result(SpeechRecognizer recognizer,String text){Bundle bundle=new Bundle();bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<>(List.of(text)));Shadows.shadowOf(recognizer).triggerOnResults(bundle);idle();}
    @Test public void finalTextMustBeReviewedThenOriginalSendKeepsOneInputAndNativeApprovalBeforeRealWrite()throws Exception{
        SpeechRecognizer recognizer=listen();Bundle partial=new Bundle();partial.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<>(List.of("不要执行这份部分文字")));Shadows.shadowOf(recognizer).triggerOnPartialResults(partial);idle();assertEquals(0,models.get());assertEquals(0,app.tasks.list().length());assertFalse(controller.getState().getVoiceInput().optBoolean("reviewAvailable"));
        result(recognizer,"创建一个错误名称的项目");JSONObject review=controller.getState().getVoiceInput();String input=review.getString("inputId");assertTrue(review.getBoolean("reviewAvailable"));assertFalse(review.getBoolean("leaseHeld"));assertEquals(0,models.get());assertEquals(0,app.tasks.list().length());
        String edited=controller.useVoiceDraft(input,"创建一个项目，写入前让我确认",false);assertEquals("创建一个项目，写入前让我确认",edited);idle();assertEquals(0,models.get());assertEquals(0,app.tasks.list().length());assertTrue(controller.getState().getVoiceInput().getBoolean("staged"));
        controller.sendChat(edited);until(()->app.tasks.list().length()==1&&app.tasks.list().getJSONObject(0).optString("status").equals("awaiting_user"));
        String taskId=app.tasks.list().getJSONObject(0).getString("id");JSONObject task=app.tasks.get(taskId,false);assertEquals(0,app.features.module("workspace").snapshot().getJSONArray("projects").length());assertEquals(1,task.getJSONArray("approvals").length());
        until(()->controller.getState().getChatReceipts().containsKey(input));JSONObject receipt=controller.getState().getChatReceipts().get(input);assertEquals(taskId,receipt.getString("taskId"));assertEquals(input,receipt.getString("clientMessageId"));assertEquals(input,controller.getState().getVoiceInput().getString("submittedClientMessageId"));
        JSONObject approval=task.getJSONArray("approvals").getJSONObject(0);app.dispatchOwnerAction("approveCapability",new JSONObject().put("taskId",taskId).put("approvalId",approval.getString("id")).put("approved",true));
        until(()->app.features.module("workspace").snapshot().getJSONArray("projects").length()==1&&app.tasks.get(taskId,false).optString("status").equals("needs_confirmation"));
        assertEquals(1,app.tasks.list().length());assertEquals(3,models.get());assertFalse(app.tasks.get(taskId,false).optBoolean("cancelRequested"));
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE);Shadows.shadowOf((AudioManager)app.getSystemService(Context.AUDIO_SERVICE)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        controller.speak(app.tasks.get(taskId,false).getString("summary"));TextToSpeech tts=ShadowTextToSpeech.getLastTextToSpeechInstance();assertNotNull(tts);Shadows.shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS);for(int i=0;i<30&&Shadows.shadowOf(tts).getLastSpokenText()==null;i++)Shadows.shadowOf(Looper.getMainLooper()).runOneTask();assertNotNull(Shadows.shadowOf(tts).getLastSpokenText());controller.stopSpeaking();idle();assertFalse(app.tasks.get(taskId,false).optBoolean("cancelRequested"));assertEquals("needs_confirmation",app.tasks.get(taskId,false).getString("status"));
    }
    @Test public void missingEngineAndPermissionGrantNeverStartOrFallbackWithoutAnotherOwnerClick()throws Exception{
        controller.startSystemVoiceInput(false);idle();assertNotNull(controller.getState().getError());assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer());assertEquals(0,app.tasks.list().length());assertEquals(0,models.get());
        service();controller.startSystemVoiceInput(false);idle();assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer());assertEquals("idle",controller.getState().getVoiceInput().optString("phase","idle"));
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);Field permission=MediaController.class.getDeclaredField("AUDIO_PERMISSION_REQUEST");permission.setAccessible(true);activity.get().onRequestPermissionsResult(permission.getInt(null),new String[]{Manifest.permission.RECORD_AUDIO},new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED});idle();assertNull("grant alone must not start capture",ShadowSpeechRecognizer.getLatestSpeechRecognizer());
        JSONObject info=media.systemSpeechInfo();assertTrue(info.getBoolean("recognitionAvailable"));assertEquals("unknown",info.getString("recognitionNetworkUse"));assertEquals("unknown",info.getString("recognitionLanguageSupport"));assertEquals("android_system_default",info.getString("provider"));assertEquals(0,models.get());assertEquals(0,app.tasks.list().length());
    }
    @Test public void switchingInputOrLeavingForegroundRejectsLateFinalAndDoesNotResumeCapture()throws Exception{
        SpeechRecognizer old=listen();String oldId=controller.getState().getVoiceInput().getString("inputId");controller.newConversation();idle();assertEquals("cancelled",controller.getState().getVoiceInput().getString("phase"));
        controller.startSystemVoiceInput(false);idle();String next=controller.getState().getVoiceInput().getString("inputId");assertNotEquals(oldId,next);result(old,"旧输入不得进入新对话");assertEquals(next,controller.getState().getVoiceInput().getString("inputId"));assertFalse(controller.getState().getVoiceInput().optBoolean("reviewAvailable"));
        SpeechRecognizer latest=ShadowSpeechRecognizer.getLatestSpeechRecognizer();controller.realtimeForeground(false);idle();result(latest,"后台迟到结果不得发送");controller.realtimeForeground(true);idle();JSONObject state=controller.getState().getVoiceInput();assertEquals("cancelled",state.getString("phase"));assertFalse(state.getBoolean("active"));assertFalse(state.getBoolean("leaseHeld"));assertFalse(state.getBoolean("reviewAvailable"));assertEquals(0,app.tasks.list().length());assertEquals(0,models.get());
    }
    @After public void cleanup()throws Exception{
        if(activity!=null)activity.pause().stop().destroy();idle();if(server!=null)server.stop(0);if(app!=null){ExecutorService workers=(ExecutorService)get(app,"capabilityWorkers");workers.shutdownNow();workers.awaitTermination(2,TimeUnit.SECONDS);app.features.close();((android.database.sqlite.SQLiteDatabase)get(app.tasks,"db")).close();}java.security.Security.removeProvider("VoiceDraftKeyStore");
        for(String name:new String[]{"realtimeAudioOwner","audioReleaseUnknown"}){Field field=MediaController.class.getDeclaredField(name);field.setAccessible(true);field.set(null,name.equals("audioReleaseUnknown")?false:null);}
    }
}
