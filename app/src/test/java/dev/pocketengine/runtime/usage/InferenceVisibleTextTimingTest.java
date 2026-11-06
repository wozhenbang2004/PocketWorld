package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Content-free timing at the original physical invocation; no provider or device claim. */
public final class InferenceVisibleTextTimingTest {
    private InferenceCapture capture(List<InferenceReceipt> receipts)throws Exception {
        ModelConfig config=new ModelConfig("https://fixture.invalid/v1","fixture-key","fixture-model",false,4,10,"openai_chat",256);
        return new InferenceCapture(config,new RunControl(),new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){
            public void started(InferenceReceipt receipt){receipts.add(receipt);}
            public void finished(InferenceReceipt receipt){receipts.add(receipt);}
        }));
    }
    private void absent(InferenceReceipt receipt)throws Exception {
        JSONObject value=receipt.toJson();assertTrue(value.isNull("firstVisibleTextAt"));
        assertTrue(value.isNull("firstVisibleTextElapsedNanos"));assertTrue(value.isNull("timingEvidence"));
    }
    @Test public void firstProviderTextIsFrozenAndSurvivesFailureWithoutSavingContent()throws Exception {
        List<InferenceReceipt> receipts=new ArrayList<>();InferenceCapture call=capture(receipts);
        call.observedVisibleText("before dispatch");long beforeDispatch=System.nanoTime();call.dispatch();
        call.observedVisibleText(null);call.observedVisibleText("");
        long earliest=System.currentTimeMillis();call.observedVisibleText("private first text");
        long latest=System.currentTimeMillis(),firstUpperNanos=System.nanoTime()-beforeDispatch;
        Thread.sleep(10);call.observedVisibleText("private later text");call.finish(InferenceReceipt.Outcome.FAILED);
        call.observedVisibleText("after finish");
        assertEquals(2,receipts.size());absent(receipts.get(0));InferenceReceipt finished=receipts.get(1);
        assertEquals(InferenceReceipt.Outcome.FAILED,finished.outcome);assertEquals("provider_text_delta",finished.timingEvidence);
        assertTrue(finished.firstVisibleTextAt>=earliest&&finished.firstVisibleTextAt<=latest);
        assertTrue(finished.firstVisibleTextElapsedNanos>=0&&finished.firstVisibleTextElapsedNanos<=firstUpperNanos);
        assertTrue(finished.firstVisibleTextElapsedNanos<=finished.elapsedNanos);
        assertNotNull(finished.lastVisibleTextElapsedNanos);assertTrue(finished.lastVisibleTextElapsedNanos>finished.firstVisibleTextElapsedNanos);
        assertTrue(finished.lastVisibleTextElapsedNanos<=finished.elapsedNanos);
        JSONObject projected=ExecutionTraceProjection.model(finished.toJson(),1);
        assertTrue(projected.getDouble("generationDurationMs")>0);assertEquals("client_first_to_last_visible_text_delta",projected.getString("generationTimingMeaning"));
        assertFalse(finished.toJson().toString().contains("private"));assertFalse(finished.toJson().toString().contains("before dispatch"));
    }
    @Test public void emptyAndIncompleteUnicodeHaveNoInventedTimingButACompletedSplitCharacterDoes()throws Exception {
        List<InferenceReceipt> empty=new ArrayList<>();InferenceCapture emptyCall=capture(empty);emptyCall.dispatch();
        emptyCall.observedVisibleText("");emptyCall.observedVisibleText("\uD83D");emptyCall.finish(InferenceReceipt.Outcome.FAILED);
        absent(empty.get(1));
        List<InferenceReceipt> split=new ArrayList<>();InferenceCapture splitCall=capture(split);splitCall.dispatch();
        splitCall.observedVisibleText("\uD83D");long earliest=System.currentTimeMillis();splitCall.observedVisibleText("\uDE42");
        splitCall.finish(InferenceReceipt.Outcome.COMPLETED);
        assertTrue(split.get(1).firstVisibleTextAt>=earliest);assertEquals("provider_text_delta",split.get(1).timingEvidence);
    }
    @Test public void legacyConstructorAndNoTextCancellationKeepNullableVersionOneFields()throws Exception {
        InferenceReceipt legacy=new InferenceReceipt("legacy",InferenceReceipt.Context.unattributed(),InferenceReceipt.Outcome.COMPLETED,
                100,110,10_000_000,new JSONObject(),new JSONObject());
        absent(legacy);assertEquals(1,legacy.toJson().getInt("receiptVersion"));
        List<InferenceReceipt> receipts=new ArrayList<>();InferenceCapture call=capture(receipts);call.dispatch();
        call.finish(InferenceReceipt.Outcome.CANCELLED);call.observedVisibleText("late");absent(receipts.get(1));
    }
    @Test public void requestOptionsAreFinalWireAllowlistedAndRemainContentFree()throws Exception {
        List<InferenceReceipt> receipts=new ArrayList<>();InferenceCapture call=capture(receipts);
        JSONObject wire=new JSONObject().put("stream",true).put("temperature",0.25).put("max_tokens",512)
                .put("messages",new org.json.JSONArray().put(new JSONObject().put("role","user").put("content","PRIVATE-PROMPT")))
                .put("metadata",new JSONObject().put("apiKey","fixture-key")).put("authorization","fixture-key")
                .put("reasoning",new JSONObject().put("effort","high").put("private_notes","PRIVATE-NOTES"));
        call.requestProjection(wire);call.dispatch();call.finish(InferenceReceipt.Outcome.COMPLETED);
        JSONObject route=receipts.get(1).toJson().getJSONObject("route"),options=route.getJSONObject("requestOptions");
        assertTrue(options.getBoolean("stream"));assertEquals(0.25,options.getDouble("temperature"),0);assertEquals(512,options.getInt("max_tokens"));assertEquals("high",options.getJSONObject("reasoning").getString("effort"));
        assertEquals(options.toString(),receipts.get(0).toJson().getJSONObject("route").getJSONObject("requestOptions").toString());
        assertFalse(route.toString().contains("PRIVATE-"));assertFalse(route.toString().contains("fixture-key"));assertFalse(options.has("messages"));assertFalse(options.has("authorization"));
    }
}
