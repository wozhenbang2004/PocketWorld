package dev.pocketengine.browser;

import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.*;

/** Actual task port/projection boundary with explicit driver data. Not a DOM/WebView/device test. */
public final class BrowserPresentedReadTest {
    static JSONObject completeObservation(BrowserTaskSpaceTest.Harness h,String tab,JSONObject content)throws Exception {
        h.driver.deferObserve=true;h.driver.deferred=null;ExecutorService worker=Executors.newSingleThreadExecutor();
        try{Future<JSONObject> future=worker.submit(()->h.observe(tab));long until=System.nanoTime()+2_000_000_000L;while(h.driver.deferred==null&&System.nanoTime()<until)Thread.sleep(2);assertNotNull(h.driver.deferred);
            h.ui.call(()->{JSONObject captured=h.driver.observation(h.driver.deferredTab);for(java.util.Iterator<String> keys=content.keys();keys.hasNext();){String k=keys.next();captured.put(k,content.get(k));}h.driver.deferred.complete(captured);return null;});return future.get(3,TimeUnit.SECONDS);
        }finally{h.driver.deferObserve=false;worker.shutdownNow();worker.awaitTermination(1,TimeUnit.SECONDS);}
    }
    @Test public void modelOmittedCandidatesCannotBeApprovedEvenThoughTheNativeObservationRegisteredThem()throws Exception {try(BrowserTaskSpaceTest.Harness h=new BrowserTaskSpaceTest.Harness(true)){
        String tab=h.open("many-controls");JSONArray rows=new JSONArray();String destination="https://example.com/"+"exact".repeat(220);for(int i=0;i<250;i++)rows.put(j("ref","n"+i,"tag","A","text","按键".repeat(70),"href",destination+"?row="+i));
        JSONObject shown=completeObservation(h,tab,j("elements",rows));int returned=shown.getJSONArray("elements").length();assertTrue(returned>0&&returned<250);assertEquals(250-returned,shown.getInt("elementsOmitted"));int approvals=h.approvals.get();
        JSONObject hidden=h.invoke("click",j("tabId",tab,"observationId",shown.getString("observationId"),"ref","n249"));assertFalse(hidden.getBoolean("accepted"));assertEquals(approvals,h.approvals.get());assertEquals(0,h.driver.mutableDispatches);
        JSONObject accepted=h.invoke("click",j("tabId",tab,"observationId",shown.getString("observationId"),"ref","n0"));assertTrue(accepted.toString(),accepted.getBoolean("accepted"));assertEquals(1,h.driver.mutableDispatches);assertEquals(destination+"?row=0",h.driver.lastExecuteArgs.getJSONObject("_nativeTargetPreview").getString("href"));
    }}
    @Test public void presentedSetCannotInventReferencesAndDoesNotExtendTheOldObservation()throws Exception {try(BrowserTaskSpaceTest.Harness h=new BrowserTaskSpaceTest.Harness(true)){
        String tab=h.open("presented");JSONObject shown=h.observe(tab),forged=new JSONObject(shown.toString());forged.getJSONArray("elements").put(j("ref","n99"));assertThrows(Exception.class,()->h.ui.call(()->h.space.presentedObservation(forged)));
        JSONObject pin=h.ui.call(()->h.space.pin(j("tabId",tab,"observationId",shown.getString("observationId"),"ref","n0")));assertEquals("n0",pin.getJSONObject("targetPreview").getString("ref"));h.invoke("navigate",j("tabId",tab,"url","https://example.com/replaced"));assertThrows(Exception.class,()->h.ui.call(()->h.space.presentedObservation(shown)));assertEquals(0,h.driver.mutableDispatches);
    }}
    @Test public void semanticSourceKeepsFullRepresentationAndCannotPretendADeletionPatch()throws Exception {
        JSONObject actual=BrowserSemanticRead.result(raw("text"));JSONObject delivery=actual.getJSONObject("read").getJSONObject("delivery");assertEquals("full",delivery.getString("format"));assertTrue(delivery.getString("revision").matches("[a-f0-9]{64}"));assertFalse(delivery.getBoolean("deletionsInferred"));
        JSONObject facts=BrowserSemanticRead.sourceFacts(actual.getJSONObject("read"));BrowserSemanticRead.validateSourceFacts(facts);JSONObject forged=new JSONObject(facts.toString());forged.getJSONObject("delivery").put("format","delta");assertThrows(SecurityException.class,()->BrowserSemanticRead.validateSourceFacts(forged));
        JSONObject prior=new JSONObject(facts.toString());prior.remove("delivery");BrowserSemanticRead.validateSourceFacts(prior); // Existing durable sources remain valid.
    }
    @Test public void refreshRequiresAnExactRegionAndCannotBeHiddenInACursorOrAnOutline()throws Exception {
        String ref=UUID.randomUUID()+":r1",revision="a".repeat(64);BrowserSemanticRead.validate(j("mode","text","regionRef",ref,"baseRevision",revision));
        assertThrows(Exception.class,()->BrowserSemanticRead.validate(j("mode","text","baseRevision",revision)));assertThrows(Exception.class,()->BrowserSemanticRead.validate(j("mode","outline","regionRef",ref,"baseRevision",revision)));assertThrows(Exception.class,()->BrowserSemanticRead.validate(j("mode","text","cursor",UUID.randomUUID()+":c0","baseRevision",revision)));assertThrows(Exception.class,()->BrowserSemanticRead.validate(j("mode","text","regionRef",ref,"baseRevision","wrong")));
    }
}
