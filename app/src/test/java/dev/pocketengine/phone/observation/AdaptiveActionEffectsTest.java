package dev.pocketengine.phone.observation;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneUiSignals;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.phone.observation.PhoneCollectionFixtures.*;

/** Explicit physical-port interface fixture. No Android accessibility action or display was exercised. */
public final class AdaptiveActionEffectsTest {
    static final class Driver implements PhonePort,NativePhoneSignals {
        final AtomicLong clock=new AtomicLong(1000);final PhoneUiSignals events=new PhoneUiSignals("fixture-service",clock::get);final List<Boolean> images=new CopyOnWriteArrayList<>();
        volatile String last,value="old";volatile int effects,reads,postReadMonitorChecks;volatile boolean unknown,enforcePostReadMonitor=true;volatile AdaptivePhonePort adapter;CountDownLatch postReadEntered,postReadRelease;
        public boolean isConnected(){return true;}
        public void clearReferences(){}
        public PhoneUiSignals uiSignals(RunControl c)throws Exception{c.check();return events;}
        public JSONObject observe(boolean image,RunControl c)throws Exception {
            images.add(image);reads++;if(effects>0&&adapter!=null&&enforcePostReadMonitor){postReadMonitorChecks++;assertFalse("post-action native read held adapter monitor",Thread.holdsLock(adapter));}
            if(effects>0&&postReadEntered!=null){CountDownLatch entered=postReadEntered,release=postReadRelease;postReadEntered=null;entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));}
            last=UUID.randomUUID().toString();JSONObject out=tree(last,value);out.put("pauseRevision",c.pauseRevision());JSONObject field=out.getJSONArray("nodes").getJSONObject(2);field.put("editable",true).put("focused",true).put("identityFingerprint",value).put("text",value.substring(0,Math.min(400,value.length())));if(value.length()>400)out.getJSONObject("treeCoverage").put("bodyTruncated",true);return out;
        }
        public JSONObject execute(String tool,JSONObject args,RunControl c)throws Exception {
            c.check();if(!last.equals(args.optString("observationId")))return j("ok",false,"accepted",false,"error","stale_observation");effects++;if(unknown)throw new java.io.IOException("fixture dispatch receipt lost");
            JSONObject receipt=j("ok",true,"accepted",true,"verified",false);if(tool.equals("phone_type")){value=args.getString("text");receipt.put("nativeTextReadback",PhoneActionEffects.inputReadback(value,args.getString("text"),false).put("mechanism","explicit_physical_interface_fixture"));}events.record(9);return receipt;
        }
    }
    static AdaptivePhonePort port(Driver d,RunControl c)throws Exception {c.bindHostRun("task","run",1);AdaptivePhonePort p=new AdaptivePhonePort(d,new AdaptivePhonePort.Scope("task","run",1,"s",5),false,false,d.clock::get);d.adapter=p;return p;}
    @Test public void acceptedInputRetainsOneEffectAndGetsMetadataOnlyStableEvidencePlusFullReadback()throws Exception {
        Driver d=new Driver();RunControl c=new RunControl();AdaptivePhonePort p=port(d,c);JSONObject before=p.observe(false,c);String wanted="value".repeat(300);JSONObject out=p.execute("phone_type",j("observationId",before.getString("observationId"),"nodeId","n2","text",wanted),c);
        assertTrue(out.toString(),out.getBoolean("accepted"));assertEquals(1,d.effects);assertTrue(out.getJSONObject("nativeTextReadback").getBoolean("exactMatch"));assertTrue(out.getJSONObject("nativeTextReadback").getBoolean("textTruncated"));assertFalse(out.getBoolean("verifiedBusinessSuccess"));assertEquals("stable_observed_scope",out.getJSONObject("actionEffects").getString("stability"));assertEquals("change_observed",out.getJSONObject("actionEffects").getJSONObject("observed").getString("status"));assertTrue(d.reads>=3);assertFalse(d.images.contains(true));
    }
    @Test public void pauseDuringPostReadReleasesMonitorAndNeverWaitsForOrReplaysAfterResume()throws Exception {
        Driver d=new Driver();RunControl c=new RunControl();AdaptivePhonePort p=port(d,c);JSONObject before=p.observe(false,c);d.postReadEntered=new CountDownLatch(1);d.postReadRelease=new CountDownLatch(1);CountDownLatch entered=d.postReadEntered;ExecutorService worker=Executors.newSingleThreadExecutor();
        try{Future<JSONObject> result=worker.submit(()->p.execute("phone_click_node",j("observationId",before.getString("observationId"),"nodeId","n2"),c));assertTrue(entered.await(1,TimeUnit.SECONDS));c.pause();p.clearReferences();d.postReadRelease.countDown();JSONObject out=result.get(1,TimeUnit.SECONDS);assertTrue(out.getBoolean("accepted"));assertEquals(1,d.effects);assertFalse(out.getBoolean("verifiedBusinessSuccess"));assertFalse(out.getJSONObject("actionEffects").getString("stability").equals("stable_observed_scope"));c.resume();assertEquals(1,d.effects);
        }finally{d.postReadRelease.countDown();worker.shutdownNow();worker.awaitTermination(1,TimeUnit.SECONDS);}
    }
    @Test public void unknownDispatchIsNotReplayedOrFollowedByAutomaticReads()throws Exception {
        Driver d=new Driver();RunControl c=new RunControl();AdaptivePhonePort p=port(d,c);JSONObject before=p.observe(false,c);int reads=d.reads;d.unknown=true;JSONObject out=p.execute("phone_click_node",j("observationId",before.getString("observationId"),"nodeId","n2"),c);assertTrue(out.isNull("accepted"));assertEquals("unknown_dispatch_not_replayed",out.getJSONObject("actionEffects").getString("stability"));assertEquals(reads,d.reads);assertEquals(1,d.effects);assertFalse(p.observe(false,c).getBoolean("ok"));assertEquals(reads,d.reads);
    }
}
