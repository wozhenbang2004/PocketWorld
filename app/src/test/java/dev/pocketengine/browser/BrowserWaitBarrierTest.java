package dev.pocketengine.browser;

import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BrowserWaitBarrierTest {
    private static JSONObject dom(long count,long quiet,boolean loading)throws Exception{return new JSONObject().put("available",true).put("mutations",count).put("quietForMs",quiet).put("loading",loading);}
    private static JSONObject network(String capture,long sequence,long quiet,int inflight)throws Exception{return new JSONObject().put("available",true).put("captureId",capture).put("sequence",sequence).put("quietForMs",quiet).put("inflight",inflight).put("coverageLost",false);}
    @Test public void combinedBarrierRequiresBothActuallyObservedScopesToBeQuiet()throws Exception {
        String capture=UUID.randomUUID().toString();BrowserWaitBarrier.Tracker tracker=new BrowserWaitBarrier.Tracker(new JSONObject().put("kind","dom_network").put("captureId",capture).put("quietMs",500));
        assertFalse(tracker.accept(dom(0,600,false),network(capture,1,600,1)));assertFalse(tracker.accept(dom(1,30,false),network(capture,2,600,0)));assertFalse(tracker.accept(dom(1,600,true),network(capture,2,600,0)));assertTrue(tracker.accept(dom(1,600,false),network(capture,2,600,0)));
        JSONObject receipt=tracker.receipt(true,false,900);assertTrue(receipt.getBoolean("conditionMet"));assertFalse(receipt.getJSONObject("barrier").getBoolean("wholePageStable"));assertFalse(receipt.getJSONObject("barrier").getBoolean("networkIdleProven"));assertFalse(receipt.getBoolean("automaticReplayAllowed"));
    }
    @Test public void mutationActivityResetsBoundedBackoffWithoutBecomingAHumanPacingRule()throws Exception {
        BrowserWaitBarrier.Tracker tracker=new BrowserWaitBarrier.Tracker(new JSONObject().put("kind","dom").put("quietMs",2000));tracker.accept(dom(0,0,false),null);assertEquals(80,tracker.delayMs());tracker.accept(dom(0,80,false),null);assertEquals(120,tracker.delayMs());tracker.accept(dom(1,0,false),null);assertEquals(80,tracker.delayMs());
        for(int i=0;i<10;i++)tracker.accept(dom(1,100,false),null);assertEquals(400,tracker.delayMs());
    }
    @Test public void unavailableOrForeignCaptureNeverClaimsConditionMet()throws Exception {
        String capture=UUID.randomUUID().toString();BrowserWaitBarrier.Tracker tracker=new BrowserWaitBarrier.Tracker(new JSONObject().put("kind","network").put("captureId",capture));assertFalse(tracker.accept(null,new JSONObject().put("available",false)));assertEquals("unavailable",tracker.receipt(true,false,50).getString("status"));assertFalse(tracker.receipt(true,false,50).getBoolean("conditionMet"));
        BrowserWaitBarrier.Tracker fresh=new BrowserWaitBarrier.Tracker(new JSONObject().put("kind","network").put("captureId",capture));assertThrows(SecurityException.class,()->fresh.accept(null,network(UUID.randomUUID().toString(),1,2000,0)));
    }
    @Test public void deadlineAndProbeBudgetsReturnActualUnmetEvidence()throws Exception {
        BrowserWaitBarrier.Tracker tracker=new BrowserWaitBarrier.Tracker(new JSONObject().put("kind","dom"));for(int i=0;i<32;i++)assertFalse(tracker.accept(dom(i,0,false),null));assertTrue(tracker.exhausted());assertFalse(tracker.receipt(false,true,6000).getBoolean("conditionMet"));assertEquals("condition_timeout",tracker.receipt(false,true,6000).getString("code"));assertTrue(tracker.receipt(false,false,6000).getJSONObject("barrier").getBoolean("probeBudgetExhausted"));assertThrows(IllegalStateException.class,()->tracker.accept(dom(32,0,false),null));
    }
    @Test public void closedSchemaNeverEnablesNetworkOrAcceptsUnboundQuietRequest()throws Exception {
        for(String input:new String[]{"{\"kind\":\"network\"}","{\"kind\":\"dom\",\"captureId\":\"anything\"}","{\"kind\":\"dom\",\"quietMs\":99}","{\"kind\":\"dom\",\"quietMs\":2001}","{\"kind\":\"dom\",\"quietMs\":100.5}","{\"kind\":\"dom\",\"enableNetwork\":true}"})assertThrows(Exception.class,()->BrowserWaitBarrier.validate(new JSONObject(input)));
        assertFalse(BrowserReadScope.readTool(BrowserNativeNetwork.BODY_TOOL));JSONObject policy=BrowserActionScope.forOwner(true,"https://example.test",20,false).review(),pin=new JSONObject().put("pageUrl","https://example.test/data");assertFalse(BrowserActionScope.eligible(policy,BrowserNativeNetwork.BODY_TOOL,new JSONObject(),pin));
    }
}
