package dev.pocketengine.features;

import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.media.RealtimeSessionModule;
import dev.pocketengine.media.VoiceConversationPort;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
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
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic native account port only: actual Application/Gateway, no HTTP/account/media service. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class RealtimeVoiceModelHostTest {
    private EngineApplication app;
    private ModelGateway.AccountInferencePort original;
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicReference<JSONObject> sent=new AtomicReference<>();
    private interface CheckedAction {void run()throws Exception;}
    @Before public void prepare()throws Exception{
        app=(EngineApplication)RuntimeEnvironment.getApplication();Field field=ModelGateway.class.getDeclaredField("accountInference");field.setAccessible(true);original=(ModelGateway.AccountInferencePort)field.get(null);
    }
    @After public void close()throws Exception{
        ModelGateway.installAccountInferencePort(original);app.features.close();
        Field workers=EngineApplication.class.getDeclaredField("capabilityWorkers");workers.setAccessible(true);ExecutorService executor=(ExecutorService)workers.get(app);executor.shutdownNow();executor.awaitTermination(3,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Field database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);((SQLiteDatabase)database.get(app.tasks)).close();
    }
    private JSONObject ref(String account)throws Exception{return new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId",account).put("accountVersion",1L).put("modelId","fixture-model");}
    private VoiceConversationPort port()throws Exception{
        RealtimeSessionModule module=(RealtimeSessionModule)app.features.module("realtime");assertTrue(module.snapshot().getJSONObject("voiceTurn").getBoolean("portReady"));
        Field voice=RealtimeSessionModule.class.getDeclaredField("voice");voice.setAccessible(true);Object turn=voice.get(module);Field callback=turn.getClass().getDeclaredField("port");callback.setAccessible(true);
        VoiceConversationPort actual=(VoiceConversationPort)callback.get(turn);assertNotNull(actual);return actual;
    }
    private JSONObject completed(String text)throws Exception{return new JSONObject().put("type","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",text)))));}
    private void fake(JSONObject completion,CheckedAction during){
        ModelGateway.installAccountInferencePort((ref,request,operation,sink)->{
            calls.incrementAndGet();
            try{
                Field bytes=ResponsesRequest.class.getDeclaredField("bytes");bytes.setAccessible(true);sent.set(new JSONObject(new String((byte[])bytes.get(request),StandardCharsets.UTF_8)));
                if(during!=null)during.run();sink.event(completion);
            }catch(Exception failure){throw new GatewayFailure("FIXTURE_FAILURE");}
        });
    }
    private void untouched()throws Exception{assertEquals(0,app.tasks.list().length());assertNull(app.activeId());assertEquals(0,app.features.module("assistant").handle("list",new JSONObject()).getJSONArray("conversations").length());}
    @Test public void actualInstalledPortRejectsUnconfiguredModelBeforeRecordingOrInference()throws Exception{
        assertThrows(IllegalStateException.class,()->port().prepare());assertEquals(0,calls.get());untouched();
    }
    @Test public void oneRealGatewayCompletionContainsOnlySystemAndUserTextWithNoToolsOrTasks()throws Exception{
        app.settings.selectAccountModel(ref("fixture-account"));fake(completed("这是一条真实路径的合成回复"),null);
        VoiceConversationPort.Request request=port().prepare();assertEquals(0,calls.get());
        assertEquals("这是一条真实路径的合成回复",request.complete("请简短问好",new RunControl()));assertEquals(1,calls.get());
        JSONObject body=sent.get();assertEquals(0,body.getJSONArray("tools").length());assertFalse(body.getBoolean("store"));assertEquals(2,body.getJSONArray("input").length());assertEquals("developer",body.getJSONArray("input").getJSONObject(0).getString("role"));assertEquals("请简短问好",body.getJSONArray("input").getJSONObject(1).getString("content"));untouched();
    }
    @Test public void cancelledTurnCannotIssueARequest()throws Exception{
        app.settings.selectAccountModel(ref("fixture-account"));fake(completed("不得朗读"),null);VoiceConversationPort.Request request=port().prepare();RunControl stopped=new RunControl();stopped.cancel();
        assertThrows(InterruptedException.class,()->request.complete("已停止",stopped));assertEquals(0,calls.get());untouched();
    }
    @Test public void ownerChangingModelDuringRecognitionCannotRerouteCapturedSpeech()throws Exception{
        app.settings.selectAccountModel(ref("fixture-account"));fake(completed("不得发送"),null);VoiceConversationPort.Request request=port().prepare();app.settings.selectAccountModel(ref("other-fixture-account"));
        assertThrows(IllegalStateException.class,()->request.complete("旧轮语音",new RunControl()));assertEquals(0,calls.get());assertEquals("other-fixture-account",app.settings.selectedAccountModel().accountId);untouched();
    }
    @Test public void changedSelectionAfterCompletionCannotBeReturnedForTtsOrOverwriteOwnerChoice()throws Exception{
        app.settings.selectAccountModel(ref("fixture-account"));fake(completed("旧模型的迟到回复"),()->app.settings.selectAccountModel(ref("other-fixture-account")));VoiceConversationPort.Request request=port().prepare();
        assertThrows(IllegalStateException.class,()->request.complete("正在切换",new RunControl()));assertEquals(1,calls.get());assertEquals("other-fixture-account",app.settings.selectedAccountModel().accountId);untouched();
    }
    @Test public void unsolicitedModelToolCallNeverCreatesAnExecutionOrAReply()throws Exception{
        app.settings.selectAccountModel(ref("fixture-account"));JSONObject unexpected=new JSONObject().put("type","completed").put("output",new JSONArray().put(new JSONObject().put("type","function_call").put("call_id","fixture-call").put("namespace","pocketworld").put("name","open_app").put("arguments","{}")));
        fake(unexpected,null);VoiceConversationPort.Request request=port().prepare();assertThrows(Exception.class,()->request.complete("打开应用",new RunControl()));assertEquals(1,calls.get());untouched();
    }
    @Test public void overlongReplyFailsWithoutTruncationOrAnotherInference()throws Exception{
        app.settings.selectAccountModel(ref("fixture-account"));fake(completed("长".repeat(4001)),null);VoiceConversationPort.Request request=port().prepare();
        assertThrows(IllegalStateException.class,()->request.complete("长回复",new RunControl()));assertEquals(1,calls.get());untouched();
    }
}
