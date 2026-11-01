package dev.pocketengine.features.memory;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.CompactionPlan;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** New pure contract evidence. Fake ports do not constitute native output storage/model proof. */
public final class ToolOutputContractTest {
    static JSONObject ref()throws Exception{String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();return new JSONObject().put("taskId",task).put("runId",run).put("epoch",1).put("tool","ssh_read_file").put("operationId","").put("sourceId",ToolOutputStore.sourceId(task,run,UUID.randomUUID().toString())).put("revision","sha256:"+"a".repeat(64)).put("captureKind","NATIVE_RESULT_DATA_JSON").put("retainedRange",new JSONObject().put("offset",0).put("chars",80).put("utf8Bytes",80).put("previewChars",80).put("unit","UTF-16")).put("retainedNativeDataComplete",true).put("upstreamTruncated",false);}
    static JSONObject metadata()throws Exception{return ToolOutputCapture.recorded("stored","",new JSONArray().put(ref())).metadata();}
    static JSONObject row(JSONObject metadata)throws Exception{return new JSONObject().put("role","tool").put("tool_call_id","native-call").put("content",new JSONObject().put("status","completed").put("data",new JSONObject().put("text","untrusted native data")).put("outputCapture",metadata).toString()).put("toolOutputCapture",new JSONObject(metadata.toString()));}
    @Test public void metadataAndRefsAreStrictDefensiveAndNeverIntegerSourceRefs()throws Exception{
        JSONObject original=metadata(),checked=ToolOutputCapture.checkedMetadata(original);original.getJSONArray("outputRefs").getJSONObject(0).put("sourceId","model-forged");assertNotEquals("model-forged",checked.getJSONArray("outputRefs").getJSONObject(0).getString("sourceId"));
        JSONObject wrong=ref().put("epoch","1");assertThrows(IllegalArgumentException.class,()->ToolOutputCapture.checkedRefs(new JSONArray().put(wrong)));JSONObject hashAsInteger=ref().put("revision",1);assertThrows(IllegalArgumentException.class,()->ToolOutputCapture.checkedRefs(new JSONArray().put(hashAsInteger)));
    }
    @Test public void metadataCannotSmuggleDiagnosticsBodyGrantOrDuplicateReferences()throws Exception{
        assertThrows(IllegalArgumentException.class,()->ToolOutputCapture.checkedMetadata(metadata().put("body","captured secret")));assertThrows(IllegalArgumentException.class,()->ToolOutputCapture.unavailable("raw exception secret"));JSONObject same=ref();assertThrows(IllegalArgumentException.class,()->ToolOutputCapture.checkedRefs(new JSONArray().put(same).put(same)));
        assertThrows(IllegalArgumentException.class,()->ToolOutputCapture.checkedMetadata(metadata().put("executionAuthorized",true)));
    }
    @Test public void partialAndUnknownProducerCompletenessCannotBecomeStoredFullOriginal()throws Exception{
        JSONObject incomplete=ref().put("upstreamTruncated",JSONObject.NULL);assertThrows(IllegalArgumentException.class,()->ToolOutputCapture.recorded("stored","",new JSONArray().put(incomplete)));assertEquals("partial",ToolOutputCapture.recorded("partial","native_output_upstream_completeness_unknown",new JSONArray().put(incomplete)).metadata().getString("status"));
    }
    @Test public void encoderCapsActualUtf8WithoutSplittingUnicodeAndHashesRetainedBytesOnly()throws Exception{
        var payload=ToolOutputEncoding.encode(new JSONObject().put("body","中🙂".repeat(300000)),text->text);assertTrue(payload.bytes<=ToolOutputCapture.MAX_OUTPUT_BYTES);assertFalse(payload.complete);MemoryBook.validateUnicode(payload.text);long bytes=0;StringBuilder reconstructed=new StringBuilder();for(var chunk:payload.chunks){bytes+=chunk.bytes;reconstructed.append(chunk.text);assertEquals(ToolOutputEncoding.fingerprint(chunk.text),chunk.fingerprint);}assertEquals(payload.text,reconstructed.toString());assertEquals(payload.bytes,bytes);assertEquals(ToolOutputEncoding.fingerprint(payload.text),payload.fingerprint);
    }
    @Test public void runnerRestoresOnlyMatchingStrictLocatorAfterShortKeyCleaning()throws Exception{
        JSONObject metadata=metadata(),receipt=new JSONObject().put("status","completed").put("data",new JSONObject().put("content","a secret a")).put("outputCapture",metadata);
        ModelConfig config=new ModelConfig("https://example.invalid/v1","a","fixture",false,4,10,"openai_chat",500);var method=AgentRunner.class.getDeclaredMethod("toolResult",JSONObject.class,JSONObject.class,ModelConfig.class);method.setAccessible(true);JSONObject result=(JSONObject)method.invoke(null,new JSONObject().put("id","native-call"),receipt,config);
        assertEquals(TaskMemoryTrace.rawFingerprint(metadata),TaskMemoryTrace.rawFingerprint(result.getJSONObject("toolOutputCapture")));JSONObject content=new JSONObject(result.getString("content"));assertEquals(TaskMemoryTrace.rawFingerprint(metadata),TaskMemoryTrace.rawFingerprint(content.getJSONObject("outputCapture")));assertFalse(content.getJSONObject("data").getString("content").contains("a"));
    }
    @Test public void mismatchedOrWrongRolePrivateCarrierIsNeverIgnoredByH4()throws Exception{
        JSONObject metadata=metadata(),good=row(metadata);assertFalse(TaskMemoryHistory.withoutOutputCarrier(good).has("toolOutputCapture"));JSONObject bad=row(metadata);bad.getJSONObject("toolOutputCapture").put("reason","native_output_capture_unavailable");assertThrows(Exception.class,()->TaskMemoryHistory.withoutOutputCarrier(bad));assertThrows(SecurityException.class,()->TaskMemoryHistory.withoutOutputCarrier(new JSONObject(good.toString()).put("role","user")));
    }
    @Test public void f012IgnoresOnlyPrivateCarrierAndStillPinsRealBodyAndPublicLocatorChanges()throws Exception{
        JSONObject original=row(metadata()),visible=TaskMemoryHistory.withoutOutputCarrier(original);var method=CompactionPlan.class.getDeclaredMethod("sameWireContent",JSONObject.class,JSONObject.class);method.setAccessible(true);assertEquals(Boolean.TRUE,method.invoke(null,original,visible));JSONObject changed=new JSONObject(visible.toString()),content=new JSONObject(changed.getString("content"));content.getJSONObject("data").put("text","real body changed");changed.put("content",content.toString());assertEquals(Boolean.FALSE,method.invoke(null,original,changed));
        JSONObject changedRef=new JSONObject(visible.toString()),body=new JSONObject(changedRef.getString("content"));body.getJSONObject("outputCapture").getJSONArray("outputRefs").getJSONObject(0).put("revision","sha256:"+"b".repeat(64));changedRef.put("content",body.toString());assertEquals(Boolean.FALSE,method.invoke(null,original,changedRef));
    }
    @Test public void sourcePortKeepsKnownPreDelegateRefusalAndDoesNotRelabelEnteredEffect()throws Exception{
        RunControl control=new RunControl();AtomicInteger entered=new AtomicInteger();ToolOutputCapture.Factory unavailable=new ToolOutputCapture.Factory(){public ToolOutputCapture.Claim begin(String n,String o,ToolOutputCapture.Kind k,RunControl c){return null;}public void checkSources(RunControl c)throws Exception{throw new ToolOutputCapture.OutputSourceUnavailable(null);}};
        CapabilityPort original=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String n){return true;}public boolean sideEffecting(String n){return true;}public JSONObject execute(String n,JSONObject a,RunControl c)throws Exception{entered.incrementAndGet();throw new java.io.IOException("actual delegate receipt unknown");}};
        ToolOutputSourcePort blocked=new ToolOutputSourcePort(original,unavailable,control);JSONObject refusal=blocked.execute("ssh_exec",new JSONObject(),control);assertEquals("not_dispatched",refusal.getString("status"));assertFalse(refusal.getBoolean("nativeDispatched"));assertEquals(0,entered.get());
        ToolOutputSourcePort passed=new ToolOutputSourcePort(original,(n,o,k,c)->null,control);assertThrows(java.io.IOException.class,()->passed.execute("ssh_exec",new JSONObject(),control));assertEquals(1,entered.get());
    }
    @Test public void sourcePortChecksAfterResponseBothSidesAndCleanupFailureCanReallyRetry()throws Exception{
        RunControl control=new RunControl();AtomicBoolean current=new AtomicBoolean(true);AtomicInteger closed=new AtomicInteger();CapabilityPort original=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String n){return true;}public boolean sideEffecting(String n){return false;}public JSONObject execute(String n,JSONObject a,RunControl c){return new JSONObject();}public JSONObject afterModelResponse(String r,String a,RunControl c){current.set(false);return null;}public void close(){if(closed.incrementAndGet()==1)throw new IllegalStateException("controlled cleanup still pending");}};
        ToolOutputCapture.Factory source=new ToolOutputCapture.Factory(){public ToolOutputCapture.Claim begin(String n,String o,ToolOutputCapture.Kind k,RunControl c){return null;}public void checkSources(RunControl c)throws Exception{if(!current.get())throw new ToolOutputCapture.OutputSourceUnavailable(null);}};
        ToolOutputSourcePort port=new ToolOutputSourcePort(original,source,control);assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->port.afterModelResponse("request","answer",control));assertThrows(IllegalStateException.class,port::close);port.close();port.close();assertEquals(2,closed.get());assertFalse(control.isCancelled());
    }
}
