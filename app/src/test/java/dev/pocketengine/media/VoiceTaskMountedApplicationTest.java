package dev.pocketengine.media;

import android.app.Activity;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.testutil.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.robolectric.shadows.ShadowTextToSpeech;
import static org.junit.Assert.*;

/** Real Application/native admission/TaskStore/Runner/workspace; HTTP/audio transport are labelled fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class VoiceTaskMountedApplicationTest {
    private EngineApplication app;private RealtimeSessionModule module;private ActivityController<Activity> owner;private MediaController media;private HttpServer server;
    private final ExecutorService callers=Executors.newSingleThreadExecutor();private final ControlledVoice voice=new ControlledVoice();private final AtomicInteger modelCalls=new AtomicInteger();
    @Before public void setup()throws Exception{
        java.security.Security.addProvider(new java.security.Provider("VoiceTaskFixtureKeyStore","1","Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});
        app=(EngineApplication)RuntimeEnvironment.getApplication();module=(RealtimeSessionModule)app.features.module("realtime");owner=Robolectric.buildActivity(Activity.class).setup();media=new MediaController(owner.get(),event->{});module.setForeground(owner.get(),media);
        ShadowTextToSpeech.addLanguageAvailability(java.util.Locale.SIMPLIFIED_CHINESE);Shadows.shadowOf((android.media.AudioManager)app.getSystemService(android.content.Context.AUDIO_SERVICE)).setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();int call=modelCalls.incrementAndGet();JSONObject message=new JSONObject().put("role","assistant");
            if(call<=2)message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","workspace-fixture-"+call).put("type","function").put("function",new JSONObject().put("name","workspace_create").put("arguments",new JSONObject().put("name","语音原任务的实际项目").toString()))));
            else message.put("content","原工作区项目已创建，请在原任务核对实际结果。");
            byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();app.settings.save(new JSONObject().put("provider","openai_chat").put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("model","fixture-model").put("clearApiKey",true).put("sendScreenshots",false).put("maxSteps",8));
    }
    private ExecutorService workers()throws Exception{Field field=EngineApplication.class.getDeclaredField("capabilityWorkers");field.setAccessible(true);return (ExecutorService)field.get(app);}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private JSONObject call(String command,JSONObject input)throws Exception{return module.handleOwner(owner.get(),command,input);}
    private JSONObject view()throws Exception{return module.snapshot().getJSONObject("voiceTask");}
    private JSONObject actions()throws Exception{JSONObject view=view();return new JSONObject().put("bindingId",view.getString("bindingId")).put("viewToken",view.getString("viewToken"));}
    private JSONObject savedTarget()throws Exception{
        RealtimeReviewedTurn reviewed=new RealtimeReviewedTurn(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,"创建项目，敏感写入先让我确认","input-native","reported-local-microphone","audio-native","openai_realtime_ga");
        app.tasks.createReviewedRealtimeTask(reviewed.commitId,reviewed.text,reviewed.source());call("recoverVoiceTask",new JSONObject().put("taskId",reviewed.commitId));return app.tasks.get(reviewed.commitId,false);
    }
    private void until(java.util.concurrent.Callable<Boolean> condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!condition.call()&&System.nanoTime()<end){idle();Thread.sleep(3);}idle();assertTrue("Original native workflow did not settle",condition.call());}
    @After public void close()throws Exception{
        if(module!=null)module.clearForeground(owner.get());if(media!=null)media.close();idle();callers.shutdownNow();if(server!=null)server.stop(0);if(app!=null){workers().shutdownNow();workers().awaitTermination(2,TimeUnit.SECONDS);app.features.close();Field db=dev.pocketengine.store.TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(app.tasks)).close();}
        if(owner!=null)owner.pause().stop().destroy();java.security.Security.removeProvider("VoiceTaskFixtureKeyStore");
        for(String name:new String[]{"realtimeAudioOwner","audioReleaseUnknown"}){Field field=MediaController.class.getDeclaredField(name);field.setAccessible(true);field.set(null,name.equals("audioReleaseUnknown")?false:null);}
    }
    @Test public void spokenFinalStartsOriginalTaskNativeApprovalCreatesActualProjectAndResultCanBeStoppedWithoutTaskCancel()throws Exception{
        module.installNaturalVoiceFactory((android,events,profile)->{voice.events=events;return voice;});call("configureNaturalVoice",new JSONObject().put("endpointUrl","wss://voice.example/realtime").put("model","fixture-voice").put("voice","fixture").put("transcriptionModel","fixture-asr").put("authenticationToken","fixture-voice-only-token"));
        JSONObject invitation=call("prepareNaturalVoice",new JSONObject()).getJSONObject("invitation");Future<JSONObject> accepting=callers.submit(()->call("acceptInvitation",new JSONObject().put("invitationId",invitation.getString("id")).put("microphone",false).put("camera",false)));until(accepting::isDone);accepting.get();
        for(String phase:new String[]{"begin","complete"})voice.events.emit(new JSONObject().put("kind","transcription").put("segmentId","input-1").put("participantIdentity","reported-local-microphone").put("trackSid","fixture-track").put("protocol","openai_realtime_ga").put("sequence",1).put("phase",phase).put("text",phase.equals("begin")?"":"创建一个项目，写入前让我确认").put("finalStream",phase.equals("complete")).put("isFinal",phase.equals("complete")).put("reportedLocalMicrophone",true));
        JSONObject turn=module.snapshot().getJSONObject("transcription").getJSONArray("turns").getJSONObject(0);JSONObject review=call("reviewTurn",new JSONObject().put("sessionId",module.snapshot().getString("sessionId")).put("turnId",turn.getString("id")).put("revision",turn.getLong("revision")));
        JSONObject receipt=call("commitVoiceTask",new JSONObject().put("sessionId",module.snapshot().getString("sessionId")).put("reviewId",review.getString("reviewId")).put("contentHash",review.getString("contentHash")));String id=receipt.getJSONObject("task").getString("id");
        until(()->app.tasks.get(id,false).optJSONArray("approvals")!=null&&app.tasks.get(id,false).getJSONArray("approvals").length()==1&&app.tasks.get(id,false).optString("status").equals("awaiting_user"));
        assertEquals(0,app.features.module("workspace").snapshot().getJSONArray("projects").length());assertEquals(1,app.tasks.list().length());assertTrue(view().getJSONObject("task").getBoolean("requiresNativeApproval"));
        call("interruptOutput",new JSONObject().put("sessionId",module.snapshot().getString("sessionId")));assertFalse(app.tasks.get(id,false).optBoolean("cancelRequested"));
        call("disconnect",new JSONObject().put("sessionId",module.snapshot().getString("sessionId")));idle();assertFalse(app.tasks.get(id,false).optBoolean("cancelRequested"));
        JSONObject approval=app.tasks.get(id,false).getJSONArray("approvals").getJSONObject(0);app.dispatchOwnerAction("approveCapability",new JSONObject().put("taskId",id).put("approvalId",approval.getString("id")).put("approved",true));
        until(()->app.features.module("workspace").snapshot().getJSONArray("projects").length()==1&&app.tasks.get(id,false).optString("status").equals("needs_confirmation"));
        assertEquals(id,view().getJSONObject("task").getString("taskId"));assertEquals(2,view().getJSONObject("task").getInt("epoch"));assertEquals(1,app.tasks.list().length());assertEquals(3,modelCalls.get());
        call("readVoiceTask",actions());idle();TextToSpeech tts=ShadowTextToSpeech.getLastTextToSpeechInstance();assertNotNull(tts);Shadows.shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS);
        for(int i=0;i<30&&Shadows.shadowOf(tts).getLastSpokenText()==null;i++)Shadows.shadowOf(Looper.getMainLooper()).runOneTask();
        assertNotNull("The real owned TTS admission must receive the original record",Shadows.shadowOf(tts).getLastSpokenText());assertTrue(Shadows.shadowOf(tts).getLastSpokenText().contains("原任务记录"));assertTrue(view().getJSONObject("readout").getBoolean("active"));
        call("interruptOutput",new JSONObject().put("readoutId",view().getJSONObject("readout").getString("readoutId")));idle();assertFalse(app.tasks.get(id,false).optBoolean("cancelRequested"));assertEquals("needs_confirmation",app.tasks.get(id,false).getString("status"));
    }
    @Test public void queuedOriginalRunCanBeCancelledBeforeRunnerPickupWithoutSendingModelRequest()throws Exception{
        CountDownLatch occupied=new CountDownLatch(2),release=new CountDownLatch(1);for(int i=0;i<2;i++)workers().execute(()->{occupied.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});assertTrue(occupied.await(3,TimeUnit.SECONDS));
        try{JSONObject saved=savedTarget();String id=saved.getString("id");call("startVoiceTask",actions());JSONObject original=app.tasks.get(id,false);assertEquals(1,original.getInt("coordinatorEpoch"));assertEquals("preparing",original.getString("status"));
            call("cancelVoiceTask",new JSONObject().put("bindingId",view().getString("bindingId")).put("viewToken",view().getString("cancelToken")));assertEquals("cancelled",app.tasks.get(id,false).getString("status"));assertEquals(original.getString("runId"),app.tasks.get(id,false).getString("runId"));assertEquals(0,modelCalls.get());
        }finally{release.countDown();}idle();assertEquals(0,modelCalls.get());
    }
    @Test public void refusedNativeWorkerSettlesReservedRunAndNeverAutomaticallyRetries()throws Exception{
        JSONObject original=savedTarget();String id=original.getString("id");workers().shutdown();call("startVoiceTask",actions());assertEquals("start_unconfirmed",view().getString("phase"));
        call("inspectVoiceTask",new JSONObject().put("bindingId",view().getString("bindingId")));assertEquals("failed",view().getJSONObject("task").getString("status"));assertEquals(1,app.tasks.get(id,false).getInt("coordinatorEpoch"));assertEquals(0,modelCalls.get());assertFalse(view().getBoolean("canStart"));assertEquals(1,app.tasks.list().length());
    }
    private static final class ControlledVoice implements RealtimeMediaSession {
        EventSink events;String state="idle";public JSONObject snapshot(){try{return new JSONObject().put("state",state).put("transport","openai_realtime_ga_websocket").put("microphoneEnabled",false).put("cameraEnabled",false).put("inputTranscriptionConfigured",true).put("resourceReleaseUnknown",false);}catch(Exception e){throw new AssertionError(e);}}
        public CompletableFuture<JSONObject> connect(String endpoint,String token,boolean microphone){state="connected";return CompletableFuture.completedFuture(snapshot());}public CompletableFuture<JSONObject> setMicrophoneEnabled(boolean enabled){return CompletableFuture.completedFuture(snapshot());}public CompletableFuture<JSONObject> interruptOutput(){return CompletableFuture.completedFuture(snapshot());}
        public void disconnect(String reason){state="disconnected";if(events!=null)events.emit(snapshot());}public void close(){disconnect("close");}
    }
}
