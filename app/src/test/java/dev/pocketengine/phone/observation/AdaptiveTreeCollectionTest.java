package dev.pocketengine.phone.observation;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.phone.observation.PhoneCollectionFixtures.*;

public final class AdaptiveTreeCollectionTest {
    static final class Driver implements PhonePort,NativeTreeScrollPort {
        final AtomicLong clock=new AtomicLong(1000);final List<Boolean> images=new ArrayList<>();int page,reads,effects;boolean unknown,noProgress,wrongSession;String last="";
        String[][] pages={{"A","B","C"},{"B","C","D"},{"C","D","E"}};
        public boolean isConnected(){return true;}public void clearReferences(){}
        public JSONObject observe(boolean image,RunControl control)throws Exception {images.add(image);last="o"+(++reads);JSONObject t=tree(last,pages[Math.min(page,pages.length-1)]).put("observedAtUptimeMs",clock.get()).put("pauseRevision",control.pauseRevision());if(wrongSession)t.put("sessionId","other").put("displayId",0);return t;}
        public JSONObject execute(String tool,JSONObject args,RunControl original){throw new AssertionError("No fallback coordinate/old action allowed");}
        public JSONObject scrollNode(JSONObject args,RunControl original,NativePhoneEntry.Admission admission)throws Exception {
            try{return NativePhoneEntry.call(original,original.pauseRevision(),admission,()->{effects++;if(unknown)throw new IllegalStateException("explicit native callback lost after entry");if(!noProgress)page++;clock.addAndGet(10);return j("ok",true,"accepted",true,"verified",false);});}
            catch(NativePhoneEntry.Refused refused){return j("ok",false,"accepted",false,"error","paused_or_changed");}
        }
    }
    static RunControl control(){RunControl c=new RunControl();c.bindHostRun("t","r",1);return c;}
    static AdaptivePhonePort port(Driver d){return new AdaptivePhonePort(d,new AdaptivePhonePort.Scope("t","r",1,"s",5),false,false,d.clock::get);}
    @Test public void collectionAndExtractionNeverScrollOrRequestAnImageImplicitly()throws Exception {
        Driver d=new Driver();AdaptivePhonePort p=port(d);RunControl c=control();p.observe(false,c);JSONObject a=start(d.last),out=p.collectTree(a,null,c);assertEquals("needs_scroll",out.getString("status"));assertEquals(0,d.effects);assertEquals(3,p.extractTree(extract(d.last),c).getInt("returnedRows"));assertEquals(1,d.reads);assertFalse(d.images.contains(true));
        p.collectTree(j("action","read","cursorId",out.getString("cursorId")),null,c);assertEquals(2,d.reads);assertEquals(0,d.effects);assertFalse(d.images.contains(true));
    }
    @Test public void semanticScrollHasOneAtomicNativeEntryAndARealFreshTreeNotABusinessProof()throws Exception {
        Driver d=new Driver();AdaptivePhonePort p=port(d);RunControl c=control();p.observe(false,c);String old=d.last;int[] admitted={0};JSONObject out=p.scrollNode(j("observationId",old,"nodeId","n0","direction","forward"),c,()->admitted[0]++);
        assertEquals(1,admitted[0]);assertEquals(1,d.effects);assertEquals(2,d.reads);assertTrue(out.getBoolean("accepted"));assertEquals("tree_changed_not_business_proof",out.getString("effect"));assertFalse(out.getBoolean("verifiedBusinessSuccess"));assertNotEquals(old,out.getString("afterObservationId"));assertEquals(9,out.getJSONObject("targetRef").getInt("windowId"));assertEquals("[]",out.getJSONObject("targetRef").getJSONArray("path").toString());assertFalse(d.images.contains(true));assertEquals("stale_observation",p.queryTree(j("observationId",old,"selector",new JSONObject()),c).getString("error"));
    }
    @Test public void pauseDuringNativeAdmissionDoesNotEnterAndDoesNotWaitForResume()throws Exception {
        Driver d=new Driver();AdaptivePhonePort p=port(d);RunControl c=control();p.observe(false,c);long at=System.nanoTime();JSONObject out=p.scrollNode(j("observationId",d.last,"nodeId","n0","direction","forward"),c,c::pause);assertEquals(0,d.effects);assertFalse(out.getBoolean("accepted"));assertTrue((System.nanoTime()-at)/1_000_000<500);
    }
    @Test public void lostReceiptAfterNativeEntryRetainsReconciliationAndNeverReplays()throws Exception {
        Driver d=new Driver();d.unknown=true;AdaptivePhonePort p=port(d);RunControl c=control();p.observe(false,c);String old=d.last;JSONObject out=p.scrollNode(j("observationId",old,"nodeId","n0","direction","forward"),c,()->{});assertTrue(out.isNull("accepted"));assertEquals(1,d.effects);out=p.scrollNode(j("observationId",old,"nodeId","n0","direction","forward"),c,()->{});assertEquals("reconciliation_required",out.getString("error"));assertEquals(1,d.effects);
    }
    @Test public void acceptedScrollWithNoChangeOrWrongSessionDoesNotInventAResult()throws Exception {
        Driver d=new Driver();d.noProgress=true;AdaptivePhonePort p=port(d);RunControl c=control();p.observe(false,c);JSONObject out=p.scrollNode(j("observationId",d.last,"nodeId","n0","direction","forward"),c,()->{});assertTrue(out.getBoolean("accepted"));assertEquals("no_change_observed",out.getString("effect"));assertEquals(1,d.effects);assertTrue(d.reads<=7);assertFalse(d.images.contains(true));
        Driver wrong=new Driver();AdaptivePhonePort q=port(wrong);RunControl other=control();q.observe(false,other);String id=wrong.last;wrong.wrongSession=true;out=q.scrollNode(j("observationId",id,"nodeId","n0","direction","forward"),other,()->{});assertTrue(out.getBoolean("accepted"));assertFalse(out.getBoolean("freshObservationAvailable"));assertFalse(out.has("observation"));assertFalse(wrong.images.contains(true));
    }
    @Test public void cursorCannotBeRestoredIntoAnotherAdapterOrAfterPause()throws Exception {
        Driver d=new Driver();AdaptivePhonePort p=port(d);RunControl c=control();p.observe(false,c);String cursor=p.collectTree(start(d.last),null,c).getString("cursorId");AdaptivePhonePort fresh=port(d);assertEquals("cursor_unavailable",fresh.collectTree(j("action","read","cursorId",cursor),null,c).getString("error"));c.pause();c.resume();JSONObject out=p.collectTree(j("action","read","cursorId",cursor),null,c);assertEquals("scope_or_pause_changed",out.getString("stoppedBecause"));assertFalse(out.has("observation"));assertFalse(out.has("nextScroll"));assertEquals(0,d.effects);
    }
}
